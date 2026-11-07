package io.github.apikeyportal.integration.sendgrid;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.apikeyportal.integration.ProvisioningException;

/**
 * The slice of the SendGrid v3 API this portal needs: read what the portal's
 * own key may do, mint API keys, and delete them.
 * <p>
 * SendGrid only lets a key grant scopes it holds itself, so the scopes of the
 * portal's key are the ceiling for every key minted through it.
 */
@Component
public class SendGridClient {

    /** SendGrid's limit on a key's name. */
    static final int MAX_NAME = 100;

    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final int MAX_DETAIL_LENGTH = 300;

    private final RestClient restClient;

    @Autowired
    public SendGridClient(SendGridProperties properties) {
        this(defaultBuilder(), properties);
    }

    SendGridClient(RestClient.Builder builder, SendGridProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /** The scopes the calling key holds; doubles as the connection test. */
    public List<String> scopes(String adminKey) {
        try {
            ScopesResponse response = restClient.get()
                    .uri("/v3/scopes")
                    .header("Authorization", "Bearer " + adminKey)
                    .retrieve()
                    .body(ScopesResponse.class);
            return response == null || response.scopes() == null ? List.of() : response.scopes();
        } catch (RestClientResponseException e) {
            throw translate("SendGrid rejected the API key", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /** Mints an API key. Its value is in this response and nowhere else, ever again. */
    public CreatedKey createKey(String adminKey, String name, List<String> scopes) {
        String trimmed = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        try {
            CreatedKey created = restClient.post()
                    .uri("/v3/api_keys")
                    .header("Authorization", "Bearer " + adminKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", trimmed, "scopes", scopes))
                    .retrieve()
                    .body(CreatedKey.class);
            if (created == null || created.id() == null || created.apiKey() == null || created.apiKey().isBlank()) {
                throw new ProvisioningException("SendGrid's response did not include the key's value");
            }
            return created;
        } catch (RestClientResponseException e) {
            throw translate("Could not create the API key", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /** @return false if SendGrid no longer had the key - somebody deleted it first */
    public boolean deleteKey(String adminKey, String keyId) {
        try {
            restClient.delete()
                    .uri("/v3/api_keys/" + URLEncoder.encode(keyId, StandardCharsets.UTF_8))
                    .header("Authorization", "Bearer " + adminKey)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw translate("Could not delete the API key", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    private static ProvisioningException translate(String action, RestClientResponseException e) {
        String detail = extractDetail(e.getResponseBodyAsString());
        return new ProvisioningException(action + " (HTTP " + e.getStatusCode().value() + ")"
                + (detail != null ? ": " + detail : ""), e);
    }

    private static ProvisioningException unreachable(RestClientException e) {
        return new ProvisioningException("Could not reach SendGrid: " + e.getMessage(), e);
    }

    private static String extractDetail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher matcher = ERROR_MESSAGE.matcher(body);
        if (matcher.find() && !matcher.group(1).isBlank()) {
            String detail = matcher.group(1);
            return detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) + "…" : detail;
        }
        return null;
    }

    /** @param id SendGrid's id for the key - what a deletion targets; not secret */
    public record CreatedKey(@JsonProperty("api_key_id") String id, @JsonProperty("api_key") String apiKey) {
    }

    record ScopesResponse(@JsonProperty("scopes") List<String> scopes) {
    }
}
