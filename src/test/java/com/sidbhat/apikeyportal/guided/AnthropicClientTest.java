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

import com.sidbhat.apikeyportal.integration.ProvisioningException;

class AnthropicClientTest {

    private static final String API = "https://anthropic.test";

    private MockRestServiceServer server;
    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnthropicClient(builder, new AnthropicClient.AnthropicProperties(API));
    }

    @Test
    void acceptsKeyWhenModelListingSucceeds() {
        server.expect(requestTo(API + "/v1/models?limit=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "sk-ant-abc"))
                .andExpect(header("anthropic-version", AnthropicClient.API_VERSION))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.verifyKey("sk-ant-abc")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void translatesRejectedKeyIntoReadableMessage() {
        server.expect(requestTo(API + "/v1/models?limit=1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));

        assertThatThrownBy(() -> client.verifyKey("sk-ant-wrong"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Anthropic did not accept the key")
                .hasMessageContaining("401");
    }
}
