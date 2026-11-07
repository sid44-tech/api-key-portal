package io.github.apikeyportal.integration.twilio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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

import io.github.apikeyportal.integration.ProvisioningException;

class TwilioClientTest {

    private static final String BASE = "https://twilio.test";
    private static final String ACCOUNT = "AC" + "0".repeat(32);
    private static final String KEYS = BASE + "/2010-04-01/Accounts/" + ACCOUNT + "/Keys";
    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("SKportal:portal-secret".getBytes(StandardCharsets.UTF_8));

    private MockRestServiceServer server;
    private TwilioClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TwilioClient(builder, new TwilioProperties(BASE));
    }

    @Test
    void theConnectionTestListsKeysSoAStandardKeyIsCaught() {
        server.expect(requestTo(KEYS + ".json?PageSize=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", AUTH))
                .andRespond(withSuccess("{\"keys\": []}", MediaType.APPLICATION_JSON));

        client.verifyKeyAccess(ACCOUNT, "SKportal", "portal-secret");
        server.verify();
    }

    @Test
    void mintsAKeyWithTheGivenFriendlyName() {
        server.expect(requestTo(KEYS + ".json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", AUTH))
                .andExpect(content().string("FriendlyName=SMS+sender"))
                .andRespond(withSuccess("""
                        {"sid": "SKnew", "friendly_name": "SMS sender", "secret": "s3cret"}
                        """, MediaType.APPLICATION_JSON));

        TwilioClient.CreatedKey key = client.createKey(ACCOUNT, "SKportal", "portal-secret", "SMS sender");

        assertThat(key.sid()).isEqualTo("SKnew");
        assertThat(key.secret()).isEqualTo("s3cret");
    }

    @Test
    void aLongNameIsCutToTwiliosLimit() {
        server.expect(requestTo(KEYS + ".json"))
                .andExpect(content().string("FriendlyName=" + "x".repeat(64)))
                .andRespond(withSuccess("{\"sid\": \"SKnew\", \"secret\": \"s\"}", MediaType.APPLICATION_JSON));

        client.createKey(ACCOUNT, "SKportal", "portal-secret", "x".repeat(80));
        server.verify();
    }

    @Test
    void twiliosOwnErrorMessageIsPassedAlong() {
        server.expect(requestTo(KEYS + ".json?PageSize=1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": 20003, \"message\": \"Authenticate\", \"status\": 401}"));

        assertThatThrownBy(() -> client.verifyKeyAccess(ACCOUNT, "SKportal", "portal-secret"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("Authenticate");
    }

    @Test
    void deletesAKeyAndTreatsAMissingOneAsAlreadyGone() {
        server.expect(requestTo(KEYS + "/SKold.json"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", AUTH))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(KEYS + "/SKgone.json"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": 20404, \"message\": \"The requested resource was not found\"}"));

        assertThat(client.deleteKey(ACCOUNT, "SKportal", "portal-secret", "SKold")).isTrue();
        assertThat(client.deleteKey(ACCOUNT, "SKportal", "portal-secret", "SKgone")).isFalse();
    }
}
