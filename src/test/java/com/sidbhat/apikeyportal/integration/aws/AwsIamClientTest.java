package com.sidbhat.apikeyportal.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.aws.AwsIamClient.CreatedAccessKey;
import com.sidbhat.apikeyportal.integration.aws.AwsIamClient.CreatedUser;

class AwsIamClientTest {

    private static final String IAM = "https://iam.test";
    private static final String STS = "https://sts.test";
    private static final String KEY_ID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    private MockRestServiceServer server;
    private AwsIamClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AwsIamClient(builder, new AwsProperties(IAM, STS));
    }

    @Test
    void theConnectionTestAsksStsWhoTheCredentialsAre() {
        server.expect(requestTo(STS + "/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("Action=GetCallerIdentity&Version=2011-06-15"))
                // every call carries a SigV4 signature over these credentials
                .andExpect(header("Authorization", Matchers.startsWith(
                        "AWS4-HMAC-SHA256 Credential=" + KEY_ID + "/")))
                .andExpect(header("Authorization", Matchers.containsString("/us-east-1/sts/aws4_request")))
                .andExpect(header("X-Amz-Date", Matchers.matchesPattern("\\d{8}T\\d{6}Z")))
                .andRespond(withSuccess("""
                        <GetCallerIdentityResponse>
                          <GetCallerIdentityResult>
                            <Arn>arn:aws:iam::123456789012:user/api-key-portal</Arn>
                            <Account>123456789012</Account>
                          </GetCallerIdentityResult>
                        </GetCallerIdentityResponse>
                        """, MediaType.TEXT_XML));

        assertThat(client.callerAccount(KEY_ID, SECRET)).isEqualTo("123456789012");
    }

    @Test
    void createsAUserUnderThePortalPath() {
        server.expect(requestTo(IAM + "/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(
                        "Action=CreateUser&Version=2010-05-08&UserName=reporting-1a2b&Path=%2Fkeyportal%2F"))
                .andRespond(withSuccess("""
                        <CreateUserResponse>
                          <CreateUserResult>
                            <User>
                              <Path>/keyportal/</Path>
                              <UserName>reporting-1a2b</UserName>
                              <Arn>arn:aws:iam::123456789012:user/keyportal/reporting-1a2b</Arn>
                            </User>
                          </CreateUserResult>
                        </CreateUserResponse>
                        """, MediaType.TEXT_XML));

        CreatedUser user = client.createUser(KEY_ID, SECRET, "reporting-1a2b");

        assertThat(user.userName()).isEqualTo("reporting-1a2b");
        assertThat(user.arn()).isEqualTo("arn:aws:iam::123456789012:user/keyportal/reporting-1a2b");
    }

    @Test
    void mintsAnAccessKeyAndHandsBackBothHalves() {
        server.expect(requestTo(IAM + "/"))
                .andExpect(content().string("Action=CreateAccessKey&Version=2010-05-08&UserName=reporting-1a2b"))
                .andRespond(withSuccess("""
                        <CreateAccessKeyResponse>
                          <CreateAccessKeyResult>
                            <AccessKey>
                              <UserName>reporting-1a2b</UserName>
                              <AccessKeyId>AKIANEWKEYEXAMPLE</AccessKeyId>
                              <SecretAccessKey>fresh/Secret+Value</SecretAccessKey>
                            </AccessKey>
                          </CreateAccessKeyResult>
                        </CreateAccessKeyResponse>
                        """, MediaType.TEXT_XML));

        CreatedAccessKey key = client.createAccessKey(KEY_ID, SECRET, "reporting-1a2b");

        assertThat(key.accessKeyId()).isEqualTo("AKIANEWKEYEXAMPLE");
        assertThat(key.secretAccessKey()).isEqualTo("fresh/Secret+Value");
    }

    @Test
    void deletesOneAccessKeyOfAUserAndLeavesTheUserAlone() {
        server.expect(requestTo(IAM + "/"))
                .andExpect(content().string(
                        "Action=DeleteAccessKey&Version=2010-05-08&UserName=reporting-1a2b&AccessKeyId=AKIAOLDKEY"))
                .andRespond(withSuccess("""
                        <DeleteAccessKeyResponse>
                          <ResponseMetadata><RequestId>abc</RequestId></ResponseMetadata>
                        </DeleteAccessKeyResponse>
                        """, MediaType.TEXT_XML));

        assertThat(client.deleteAccessKey(KEY_ID, SECRET, "reporting-1a2b", "AKIAOLDKEY")).isTrue();
    }

    @Test
    void anAccessKeyAwsNoLongerHasCountsAsDeleted() {
        server.expect(requestTo(IAM + "/")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_XML)
                .body("<ErrorResponse><Error><Code>NoSuchEntity</Code>"
                        + "<Message>The Access Key with id AKIAOLDKEY cannot be found.</Message>"
                        + "</Error></ErrorResponse>"));

        // somebody deleted it in the console first; the goal was that it be gone
        assertThat(client.deleteAccessKey(KEY_ID, SECRET, "reporting-1a2b", "AKIAOLDKEY")).isFalse();
    }

    @Test
    void aRefusedAccessKeyDeletionPointsBackAtThePolicy() {
        server.expect(requestTo(IAM + "/")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_XML)
                .body("<ErrorResponse><Error><Code>AccessDenied</Code>"
                        + "<Message>User is not authorized to perform iam:DeleteAccessKey</Message>"
                        + "</Error></ErrorResponse>"));

        assertThatThrownBy(() -> client.deleteAccessKey(KEY_ID, SECRET, "reporting-1a2b", "AKIAOLDKEY"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not delete the access key")
                .hasMessageContaining("missing a permission");
    }

    @Test
    void aSecondKeyMadeByHandIsExplainedWhenRotationHitsTheLimit() {
        server.expect(requestTo(IAM + "/")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.TEXT_XML)
                .body("<ErrorResponse><Error><Code>LimitExceeded</Code>"
                        + "<Message>Cannot exceed quota for AccessKeysPerUser: 2</Message>"
                        + "</Error></ErrorResponse>"));

        assertThatThrownBy(() -> client.createAccessKey(KEY_ID, SECRET, "reporting-1a2b"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("two access keys at most");
    }

    @Test
    void aWrongAccessKeyIdIsSaidInPlainWords() {
        server.expect(requestTo(STS + "/")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_XML)
                .body("""
                        <ErrorResponse>
                          <Error>
                            <Code>InvalidClientTokenId</Code>
                            <Message>The security token included in the request is invalid.</Message>
                          </Error>
                        </ErrorResponse>
                        """));

        assertThatThrownBy(() -> client.callerAccount(KEY_ID, SECRET))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("does not recognise that access key ID");
    }

    @Test
    void aMissingPermissionPointsBackAtTheSetupPolicy() {
        server.expect(requestTo(IAM + "/")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_XML)
                .body("<ErrorResponse><Error><Code>AccessDenied</Code>"
                        + "<Message>User is not authorized to perform iam:CreateUser</Message>"
                        + "</Error></ErrorResponse>"));

        assertThatThrownBy(() -> client.createUser(KEY_ID, SECRET, "x"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("missing a permission");
    }

    @Test
    void anUnknownErrorShowsAwsOwnMessageUnescaped() {
        server.expect(requestTo(IAM + "/")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.TEXT_XML)
                .body("<ErrorResponse><Error><Code>DeleteConflict</Code>"
                        + "<Message>Cannot delete entity, must delete keys &amp; policies first.</Message>"
                        + "</Error></ErrorResponse>"));

        assertThatThrownBy(() -> client.deleteUser(KEY_ID, SECRET, "x"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("delete keys & policies first");
    }

    @Test
    void aResponseMissingTheSecretIsAnErrorNotANullKey() {
        server.expect(requestTo(IAM + "/")).andRespond(withSuccess(
                "<CreateAccessKeyResponse><CreateAccessKeyResult><AccessKey>"
                        + "<AccessKeyId>AKIANEW</AccessKeyId>"
                        + "</AccessKey></CreateAccessKeyResult></CreateAccessKeyResponse>",
                MediaType.TEXT_XML));

        assertThatThrownBy(() -> client.createAccessKey(KEY_ID, SECRET, "x"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("did not include SecretAccessKey");
    }
}
