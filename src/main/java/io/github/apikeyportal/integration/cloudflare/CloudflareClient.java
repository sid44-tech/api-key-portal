package io.github.apikeyportal.integration.cloudflare;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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
 * The slice of the Cloudflare API this portal needs: verify a token, read the
 * policies off a template token, and mint new tokens carrying those policies.
 * <p>
 * Cloudflare token policies are a small language of permission groups and
 * resource scopes - far too much to rebuild as a form. So the portal doesn't
 * try: the admin crafts <em>one</em> template token in the dashboard, where that
 * language has a proper editor, and every token the portal mints is a copy of
 * the template's policies with its own name and expiry. The policies pass
 * through this client untouched, as raw maps.
 */
@Component
public class CloudflareClient {

    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final int MAX_DETAIL_LENGTH = 300;

    private final RestClient restClient;

    @Autowired
    public CloudflareClient(CloudflareProperties properties) {
        this(defaultBuilder(), properties);
    }

    CloudflareClient(RestClient.Builder builder, CloudflareProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /** The connection test for the stored admin token: Cloudflare says whether it is active. */
    public void verifyToken(String adminToken) {
        VerifyEnvelope envelope = get("/user/tokens/verify", adminToken, VerifyEnvelope.class,
                "Cloudflare rejected the credentials");
        if (envelope.result() == null || !"active".equals(envelope.result().status())) {
            throw new ProvisioningException("Cloudflare says that token is "
                    + (envelope.result() == null ? "unknown" : envelope.result().status()) + ", not active");
        }
    }

    /**
     * Reads the template token: its name for the connection message, its policies
     * for every token minted from it. Also proves the admin token can read tokens.
     */
    public TemplateToken templateToken(String adminToken, String templateTokenId) {
        TokenEnvelope envelope = get("/user/tokens/" + templateTokenId.trim(), adminToken, TokenEnvelope.class,
                "Could not read the template token");
        if (envelope.result() == null || envelope.result().policies() == null
                || envelope.result().policies().isEmpty()) {
            throw new ProvisioningException("The template token has no policies to copy");
        }
        return new TemplateToken(envelope.result().name(), envelope.result().policies());
    }

    /**
     * Mints a token with the template's policies. The value is in this response
     * and nowhere else, ever again.
     *
     * @param expiresOn null for a token that never expires
     */
    public CreatedToken createToken(String adminToken, String name, List<Map<String, Object>> policies,
            Instant expiresOn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("policies", policies);
        if (expiresOn != null) {
            // Cloudflare wants whole seconds; fractions are rejected
            body.put("expires_on", expiresOn.truncatedTo(ChronoUnit.SECONDS).toString());
        }
        try {
            TokenEnvelope envelope = restClient.post()
                    .uri("/user/tokens")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TokenEnvelope.class);
            if (envelope == null || envelope.result() == null || envelope.result().value() == null
                    || envelope.result().value().isBlank()) {
                throw new ProvisioningException("Cloudflare's response did not include the token value");
            }
            return new CreatedToken(envelope.result().id(), envelope.result().value());
        } catch (RestClientResponseException e) {
            throw translate("Could not create the token", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    private <T> T get(String path, String adminToken, Class<T> type, String failure) {
        try {
            T body = restClient.get()
                    .uri(path)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .body(type);
            if (body == null) {
                throw new ProvisioningException(failure + ": empty response");
            }
            return body;
        } catch (RestClientResponseException e) {
            throw translate(failure, e);
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
        return new ProvisioningException("Could not reach Cloudflare: " + e.getMessage(), e);
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

    /** @param policies passed through exactly as the template declares them */
    public record TemplateToken(String name, List<Map<String, Object>> policies) {
    }

    /** @param id kept as the key's provenance, findable in the dashboard */
    public record CreatedToken(String id, String value) {
    }

    record VerifyEnvelope(@JsonProperty("result") VerifyResult result) {
    }

    record VerifyResult(@JsonProperty("id") String id, @JsonProperty("status") String status) {
    }

    record TokenEnvelope(@JsonProperty("result") TokenResult result) {
    }

    record TokenResult(@JsonProperty("id") String id, @JsonProperty("name") String name,
            @JsonProperty("value") String value,
            @JsonProperty("policies") List<Map<String, Object>> policies) {
    }
}
