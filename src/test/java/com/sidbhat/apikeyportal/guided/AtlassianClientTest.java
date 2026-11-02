package com.sidbhat.apikeyportal.guided;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sidbhat.apikeyportal.guided.AtlassianClient.AccountInfo;
import com.sidbhat.apikeyportal.guided.AtlassianClient.AtlassianProperties;
import com.sidbhat.apikeyportal.integration.ProvisioningException;

class AtlassianClientTest {

    private static final String API = "https://atlassian.test";

    private MockRestServiceServer server;
    private AtlassianClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AtlassianClient(builder, new AtlassianProperties(API));
    }

    private static String basic(String email, String token) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (email + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void verifiesTheEmailAndTokenAsABasicAuthPair() {
        server.expect(requestTo(API + "/me"))
                .andExpect(method(HttpMethod.GET))
                // the token alone proves nothing; it authenticates with the email
                .andExpect(header("Authorization", basic("alex@example.com", "atl-token")))
                .andRespond(withSuccess("""
                        {"account_id": "5b10a2", "email": "alex@example.com", "name": "Alex Carter"}
                        """, MediaType.APPLICATION_JSON));

        AccountInfo info = client.verifyToken("alex@example.com", "atl-token");

        assertThat(info.name()).isEqualTo("Alex Carter");
        assertThat(info.email()).isEqualTo("alex@example.com");
        assertThat(info.accountId()).isEqualTo("5b10a2");
    }

    @Test
    void theEmailIsTrimmedBeforeItIsSigned() {
        server.expect(requestTo(API + "/me"))
                .andExpect(header("Authorization", basic("alex@example.com", "atl-token")))
                .andRespond(withSuccess("{\"name\": \"Alex Carter\"}", MediaType.APPLICATION_JSON));

        client.verifyToken("  alex@example.com  ", "atl-token");
    }

    @Test
    void a401BlamesThePairRatherThanJustTheToken() {
        server.expect(requestTo(API + "/me"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.verifyToken("alex@example.com", "wrong"))
                .isInstanceOf(ProvisioningException.class)
                // the email is as likely to be the wrong half as the token
                .hasMessageContaining("email and token together")
                .hasMessageContaining("belongs to that email address");
    }

    @Test
    void a403SaysTheAccountMayNotHaveAccess() {
        server.expect(requestTo(API + "/me"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.verifyToken("alex@example.com", "atl-token"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("may be restricted");
    }

    @Test
    void anUnreachableAtlassianIsSaidPlainly() {
        server.expect(requestTo(API + "/me"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.verifyToken("alex@example.com", "atl-token"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("HTTP 502");
    }
}
