package io.github.apikeyportal.guided;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.apikeyportal.integration.ProvisioningException;

/**
 * Verifies an Atlassian API token by asking who it belongs to - the cheapest
 * call that proves the token works, and site-independent, so one check covers
 * Jira, Confluence and the rest of the suite.
 * <p>
 * Atlassian tokens authenticate as <em>half</em> of a Basic auth pair: the other
 * half is the account email they were created under, and a token is worthless
 * without it. That is why the walk-through asks for the email too, and why
 * verification needs both.
 */
@Component
public class AtlassianClient {

    private final RestClient client;

    @Autowired
    public AtlassianClient(AtlassianProperties properties) {
        this(defaultBuilder(), properties);
    }

    AtlassianClient(RestClient.Builder builder, AtlassianProperties properties) {
        this.client = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * @param accountEmail the address the token was created under
     * @return who Atlassian says the pair belongs to, for the key's notes
     * @throws ProvisioningException if Atlassian rejects the pair or is unreachable
     */
    public AccountInfo verifyToken(String accountEmail, String apiToken) {
        try {
            AccountInfo info = client.get()
                    .uri("/me")
                    .header("Authorization", basicAuth(accountEmail, apiToken))
                    .retrieve()
                    .body(AccountInfo.class);
            if (info == null) {
                throw new ProvisioningException("Atlassian returned an empty response for the token");
            }
            return info;
        } catch (RestClientResponseException e) {
            throw new ProvisioningException(describe(e), e);
        } catch (RestClientException e) {
            throw new ProvisioningException("Could not reach Atlassian: " + e.getMessage(), e);
        }
    }

    /**
     * A 401 here means the pair didn't match, and the email is as likely to be
     * the wrong half as the token - say so, rather than sending someone off to
     * re-create a token that was fine.
     */
    private static String describe(RestClientResponseException e) {
        return switch (e.getStatusCode().value()) {
            case 401 -> "Atlassian did not accept the email and token together (HTTP 401). Check the token "
                    + "was copied completely, hasn't been revoked, and belongs to that email address.";
            case 403 -> "Atlassian refused the token (HTTP 403). It may be restricted, or the account may "
                    + "not have access.";
            default -> "Atlassian rejected the token (HTTP " + e.getStatusCode().value() + ")";
        };
    }

    private static String basicAuth(String accountEmail, String apiToken) {
        String pair = accountEmail.trim() + ":" + apiToken;
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /** What {@code /me} reports. Nothing here is secret. */
    public record AccountInfo(@JsonProperty("account_id") String accountId,
            @JsonProperty("email") String email,
            @JsonProperty("name") String name) {
    }

    /** Overridable so tests can point at a stub. */
    @ConfigurationProperties(prefix = "portal.atlassian")
    public record AtlassianProperties(@DefaultValue("https://api.atlassian.com") String apiBaseUrl) {
    }
}
