package io.github.apikeyportal.integration.sendgrid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.apikeyportal.integration.ProvisioningException;

class SendGridClientTest {

    private static final String BASE = "https://sendgrid.test";

    private MockRestServiceServer server;
    private SendGridClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SendGridClient(builder, new SendGridProperties(BASE));
    }

    @Test
    void readsTheCallingKeysScopes() {
        server.expect(requestTo(BASE + "/v3/scopes"))
                .andExpect(header("Authorization", "Bearer SG.admin"))
                .andRespond(withSuccess("{\"scopes\": [\"mail.send\", \"api_keys.create\"]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.scopes("SG.admin")).containsExactly("mail.send", "api_keys.create");
    }

    @Test
    void mintsAKeyWithTheGivenScopes() {
        server.expect(requestTo(BASE + "/v3/api_keys"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("Newsletter"))
                .andExpect(jsonPath("$.scopes[0]").value("mail.send"))
                .andRespond(withSuccess("""
                        {"api_key": "SG.new", "api_key_id": "kid-1", "name": "Newsletter", "scopes": ["mail.send"]}
                        """, MediaType.APPLICATION_JSON));

        SendGridClient.CreatedKey key = client.createKey("SG.admin", "Newsletter", List.of("mail.send"));

        assertThat(key.id()).isEqualTo("kid-1");
        assertThat(key.apiKey()).isEqualTo("SG.new");
    }

    @Test
    void sendGridsOwnErrorMessageIsPassedAlong() {
        server.expect(requestTo(BASE + "/v3/scopes"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\": [{\"field\": null, \"message\": \"authorization required\"}]}"));

        assertThatThrownBy(() -> client.scopes("SG.wrong"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("authorization required");
    }

    @Test
    void deletesAKeyAndTreatsAMissingOneAsAlreadyGone() {
        server.expect(requestTo(BASE + "/v3/api_keys/kid-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/v3/api_keys/kid-gone"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.deleteKey("SG.admin", "kid-1")).isTrue();
        assertThat(client.deleteKey("SG.admin", "kid-gone")).isFalse();
    }

    @Test
    void thePortalKeyMustBeAbleToManageKeysAndGrantTheChosenAccess() {
        assertThatThrownBy(() -> SendGridAccess.requireKeyManagement(List.of("mail.send")))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("cannot manage API keys");
        assertThatThrownBy(() -> SendGridAccess.MAIL_SEND.scopesFor(List.of("api_keys.create", "stats.read")))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Mail Send");
    }
}
