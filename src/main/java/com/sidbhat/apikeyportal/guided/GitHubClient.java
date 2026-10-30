package com.sidbhat.apikeyportal.guided;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sidbhat.apikeyportal.integration.ProvisioningException;

/**
 * Verifies a GitHub personal access token by calling {@code GET /user} with it.
 * GitHub reports the token's expiry (fine-grained and expiring classic tokens) and
 * scopes (classic tokens) in response headers, which the portal captures so the
 * stored key carries the right metadata without the user re-typing it.
 */
@Component
public class GitHubClient {

    /** Expiry header format, e.g. {@code 2026-11-28 08:00:00 UTC}. Newer responses use ISO-8601. */
    private static final DateTimeFormatter LEGACY_EXPIRATION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz");

    private final RestClient client;

    @Autowired
    public GitHubClient(GitHubProperties properties) {
        this(defaultBuilder(), properties);
    }

    GitHubClient(RestClient.Builder builder, GitHubProperties properties) {
        this.client = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * @throws ProvisioningException if GitHub rejects the token or is unreachable
     */
    public TokenInfo verifyToken(String token) {
        try {
            ResponseEntity<UserResponse> response = client.get()
                    .uri("/user")
                    .headers(h -> {
                        h.setBearerAuth(token);
                        h.set("Accept", "application/vnd.github+json");
                        h.set("X-GitHub-Api-Version", "2022-11-28");
                    })
                    .retrieve()
                    .toEntity(UserResponse.class);
            UserResponse user = response.getBody();
            String login = user != null ? user.login() : null;
            String scopes = response.getHeaders().getFirst("x-oauth-scopes");
            LocalDate expiresOn = parseExpiration(
                    response.getHeaders().getFirst("github-authentication-token-expiration"));
            return new TokenInfo(login, expiresOn, scopes != null && !scopes.isBlank() ? scopes : null);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                throw new ProvisioningException(
                        "GitHub did not accept the token (HTTP 401). Check it was copied completely "
                        + "and hasn't been revoked or expired.", e);
            }
            throw new ProvisioningException("GitHub rejected the token (HTTP "
                    + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new ProvisioningException("Could not reach GitHub: " + e.getMessage(), e);
        }
    }

    /** Parses the expiry header, which GitHub has sent in two formats over time. */
    static LocalDate parseExpiration(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(header).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException e) {
            // fall through to the legacy format
        }
        try {
            return LocalDate.parse(header.strip(), LEGACY_EXPIRATION);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * @param login     the user the token authenticates as
     * @param expiresOn the token's expiry, if GitHub reported one (fine-grained tokens always have one)
     * @param scopes    classic-token scopes, e.g. {@code repo, read:org}; null for fine-grained tokens
     */
    public record TokenInfo(String login, LocalDate expiresOn, String scopes) {
    }

    record UserResponse(@JsonProperty("login") String login) {
    }

    /** Overridable so tests can point at a stub. */
    @ConfigurationProperties(prefix = "portal.github")
    public record GitHubProperties(@DefaultValue("https://api.github.com") String apiBaseUrl) {
    }
}
