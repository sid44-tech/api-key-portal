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
 * Verifies an Anthropic API key by listing models with it - the cheapest call that
 * proves the key works, with no token usage billed.
 */
@Component
public class AnthropicClient {

    static final String API_VERSION = "2023-06-01";

    private final RestClient client;

    @Autowired
    public AnthropicClient(AnthropicProperties properties) {
        this(defaultBuilder(), properties);
    }

    AnthropicClient(RestClient.Builder builder, AnthropicProperties properties) {
        this.client = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * @throws ProvisioningException if Anthropic rejects the key or is unreachable
     */
    public void verifyKey(String apiKey) {
        try {
            client.get()
                    .uri("/v1/models?limit=1")
                    .headers(h -> {
                        h.set("x-api-key", apiKey);
                        h.set("anthropic-version", API_VERSION);
                    })
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new ProvisioningException(
                        "Anthropic did not accept the key (HTTP 401). Check it was copied completely "
                        + "and hasn't been disabled in the console.", e);
            }
            throw new ProvisioningException("Anthropic rejected the key (HTTP "
                    + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new ProvisioningException("Could not reach Anthropic: " + e.getMessage(), e);
        }
    }

    /** Overridable so tests can point at a stub. */
    @ConfigurationProperties(prefix = "portal.anthropic")
    public record AnthropicProperties(@DefaultValue("https://api.anthropic.com") String apiBaseUrl) {
    }
}
