package com.sidbhat.apikeyportal.guided;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sidbhat.apikeyportal.guided.OpenAiClient.OpenAiProperties;
import com.sidbhat.apikeyportal.integration.ProvisioningException;

class OpenAiClientTest {

    private static final String API = "https://openai.test";

    private MockRestServiceServer server;
    private OpenAiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiClient(builder, new OpenAiProperties(API));
    }

    @Test
    void verifiesTheKeyByListingModels() {
        server.expect(requestTo(API + "/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk-proj-abc"))
                .andRespond(withSuccess("{\"data\": []}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.verifyKey("sk-proj-abc")).doesNotThrowAnyException();
    }

    @Test
    void a401SaysTheKeyWasNotAccepted() {
        server.expect(requestTo(API + "/v1/models"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.verifyKey("sk-wrong"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("did not accept the key")
                .hasMessageContaining("revoked");
    }

    @Test
    void otherRefusalsCarryTheStatus() {
        server.expect(requestTo(API + "/v1/models"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.verifyKey("sk-proj-abc"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("HTTP 429");
    }
}
