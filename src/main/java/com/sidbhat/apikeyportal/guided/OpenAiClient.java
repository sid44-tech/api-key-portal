package com.sidbhat.apikeyportal.guided;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.sidbhat.apikeyportal.integration.ProvisioningException;

/**
 * Verifies an OpenAI API key by listing models with it - the cheapest call that
 * proves the key works, with no token usage billed.
 */
@Component
public class OpenAiClient {

    private final RestClient client;

    @Autowired
    public OpenAiClient(OpenAiProperties properties) {
        this(defaultBuilder(), properties);
    }

    OpenAiClient(RestClient.Builder builder, OpenAiProperties properties) {
        this.client = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * @throws ProvisioningException if OpenAI rejects the key or is unreachable
     */
    public void verifyKey(String apiKey) {
        try {
            client.get()
                    .uri("/v1/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new ProvisioningException(
                        "OpenAI did not accept the key (HTTP 401). Check it was copied completely "
                        + "and hasn't been revoked in the platform settings.", e);
            }
            throw new ProvisioningException("OpenAI rejected the key (HTTP "
                    + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new ProvisioningException("Could not reach OpenAI: " + e.getMessage(), e);
        }
    }

    /** Overridable so tests can point at a stub. */
    @ConfigurationProperties(prefix = "portal.openai")
    public record OpenAiProperties(@DefaultValue("https://api.openai.com") String apiBaseUrl) {
    }
}
