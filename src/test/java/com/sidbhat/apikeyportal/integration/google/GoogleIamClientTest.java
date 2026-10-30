package com.sidbhat.apikeyportal.integration.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient.CreatedServiceAccount;

class GoogleIamClientTest {

    private static final String TOKEN = "https://token.test";
    private static final String IAM = "https://iam.test";

    private static String privateKeyPem;

    private MockRestServiceServer server;
    private GoogleIamClient client;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleIamClient(builder, new GoogleCloudProperties(TOKEN, IAM));
    }

    @Test
    void acquiresTokenWithSignedJwtAssertion() {
        server.expect(requestTo(TOKEN + "/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(Matchers.containsString(
                        "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer")))
                .andExpect(content().string(Matchers.containsString("assertion=eyJ")))
                .andRespond(withSuccess("{\"access_token\":\"gtok-123\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.acquireToken("portal@p.iam.gserviceaccount.com", privateKeyPem)).isEqualTo("gtok-123");
        server.verify();
    }

    @Test
    void translatesTokenErrorIntoReadableMessage() {
        server.expect(requestTo(TOKEN + "/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}"));

        assertThatThrownBy(() -> client.acquireToken("portal@p.iam.gserviceaccount.com", privateKeyPem))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Google Cloud rejected the credentials")
                .hasMessageContaining("401")
                .hasMessageContaining("Invalid JWT Signature");
    }

    @Test
    void rejectsMalformedPrivateKeyWithoutCallingGoogle() {
        assertThatThrownBy(() -> client.acquireToken("portal@p.iam.gserviceaccount.com", "not a key"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("reconnect the integration");
        server.verify();
    }

    @Test
    void createsServiceAccount() {
        server.expect(requestTo(IAM + "/v1/projects/my-project/serviceAccounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer gtok-123"))
                .andExpect(jsonPath("$.accountId").value("reporting-1a2b"))
                .andExpect(jsonPath("$.serviceAccount.displayName").value("Reporting service"))
                .andRespond(withSuccess(
                        "{\"email\":\"reporting-1a2b@my-project.iam.gserviceaccount.com\",\"uniqueId\":\"42\"}",
                        MediaType.APPLICATION_JSON));

        CreatedServiceAccount created = client.createServiceAccount("gtok-123", "my-project", "reporting-1a2b",
                "Reporting service");

        assertThat(created.email()).isEqualTo("reporting-1a2b@my-project.iam.gserviceaccount.com");
        server.verify();
    }

    @Test
    void createsKeyAndDecodesCredentialsFile() {
        String keyFile = "{\"type\":\"service_account\",\"project_id\":\"my-project\"}";
        String encoded = Base64.getEncoder().encodeToString(keyFile.getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(IAM
                        + "/v1/projects/my-project/serviceAccounts/reporting%40my-project.iam.gserviceaccount.com/keys"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.privateKeyType").value("TYPE_GOOGLE_CREDENTIALS_FILE"))
                .andRespond(withSuccess("{\"privateKeyData\":\"" + encoded + "\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createServiceAccountKey("gtok-123", "my-project",
                "reporting@my-project.iam.gserviceaccount.com")).isEqualTo(keyFile);
        server.verify();
    }

    @Test
    void translatesIamErrorIntoReadableMessage() {
        server.expect(requestTo(IAM + "/v1/projects/my-project/serviceAccounts"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":403,\"message\":\"Permission iam.serviceAccounts.create denied\"}}"));

        assertThatThrownBy(() -> client.createServiceAccount("gtok-123", "my-project", "x-1", "X"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not create the service account")
                .hasMessageContaining("403")
                .hasMessageContaining("iam.serviceAccounts.create denied");
    }

    @Test
    void deletesServiceAccount() {
        server.expect(requestTo(IAM
                        + "/v1/projects/my-project/serviceAccounts/reporting%40my-project.iam.gserviceaccount.com"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer gtok-123"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.deleteServiceAccount("gtok-123", "my-project", "reporting@my-project.iam.gserviceaccount.com");
        server.verify();
    }
}
