package com.sidbhat.apikeyportal.integration.gitlab;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
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

import com.sidbhat.apikeyportal.integration.ProvisioningException;

/**
 * The slice of the GitLab REST API this portal needs: prove a stored token can
 * see its project, and mint project access tokens in it.
 * <p>
 * Unlike the other providers, the API's address is data rather than
 * configuration - GitLab is routinely self-hosted, so each integration carries
 * its own base URL and this client builds the full address per call.
 */
@Component
public class GitLabClient {

    /**
     * Maintainer. Project access tokens act as a bot member of the project; this
     * is the lowest role that can do everything the {@code api} scope offers.
     */
    private static final int ACCESS_LEVEL = 40;

    private static final Pattern ERROR_MESSAGE =
            Pattern.compile("\"(?:message|error|error_description)\"\\s*:\\s*\"([^\"]*)\"");
    private static final int MAX_DETAIL_LENGTH = 300;

    private final RestClient restClient;

    @Autowired
    public GitLabClient() {
        this(defaultBuilder());
    }

    GitLabClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * The connection test: fetches the project with the stored token, which
     * proves the URL, the project and the token in one call.
     *
     * @param project numeric id or full path, e.g. {@code 42} or {@code group/app}
     */
    public void verifyProjectAccess(String baseUrl, String token, String project) {
        try {
            restClient.get()
                    .uri(projectUrl(baseUrl, project))
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate("GitLab rejected the credentials", e);
        } catch (RestClientException e) {
            throw unreachable(baseUrl, e);
        }
    }

    /**
     * Mints a project access token with the {@code api} scope at Maintainer
     * level. The token value is in this response and nowhere else, ever again.
     *
     * @param expiresOn required by GitLab, at most a year out
     */
    public CreatedToken createProjectAccessToken(String baseUrl, String token, String project, String name,
            LocalDate expiresOn) {
        try {
            CreatedToken created = restClient.post()
                    .uri(java.net.URI.create(projectUrl(baseUrl, project) + "/access_tokens"))
                    .header("PRIVATE-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("name", name, "scopes", List.of("api"),
                            "access_level", ACCESS_LEVEL, "expires_at", expiresOn.toString()))
                    .retrieve()
                    .body(CreatedToken.class);
            if (created == null || created.token() == null || created.token().isBlank()) {
                throw new ProvisioningException("GitLab's response did not include the token value");
            }
            return created;
        } catch (RestClientResponseException e) {
            throw translate("Could not create the project access token", e);
        } catch (RestClientException e) {
            throw unreachable(baseUrl, e);
        }
    }

    /**
     * Rotates an existing project access token: GitLab issues a new value and
     * <em>revokes the old one in the same call</em>. There is no overlap window
     * - the moment this returns, anything still using the previous value is
     * broken - which is why the portal rotates these as late as it safely can
     * and replaces the key in place rather than offering a hand-over.
     *
     * @param tokenId the id GitLab gave the token when it was created
     */
    public CreatedToken rotateProjectAccessToken(String baseUrl, String token, String project, String tokenId,
            LocalDate expiresOn) {
        try {
            CreatedToken rotated = restClient.post()
                    .uri(java.net.URI.create(projectUrl(baseUrl, project) + "/access_tokens/"
                            + URLEncoder.encode(tokenId, StandardCharsets.UTF_8) + "/rotate"))
                    .header("PRIVATE-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expires_at", expiresOn.toString()))
                    .retrieve()
                    .body(CreatedToken.class);
            if (rotated == null || rotated.token() == null || rotated.token().isBlank()) {
                throw new ProvisioningException("GitLab's rotate response did not include the new token value");
            }
            return rotated;
        } catch (RestClientResponseException e) {
            throw translate("Could not rotate the project access token", e);
        } catch (RestClientException e) {
            throw unreachable(baseUrl, e);
        }
    }

    private static java.net.URI projectUrl(String baseUrl, String project) {
        // A path like group/app goes into the URL as one encoded segment. Built
        // as a URI, not a String: RestClient treats a String as a template and
        // would encode the % itself a second time.
        return java.net.URI.create(trimTrailingSlash(baseUrl) + "/api/v4/projects/"
                + URLEncoder.encode(project.trim(), StandardCharsets.UTF_8));
    }

    private static String trimTrailingSlash(String baseUrl) {
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static ProvisioningException translate(String action, RestClientResponseException e) {
        String detail = extractDetail(e.getResponseBodyAsString());
        return new ProvisioningException(action + " (HTTP " + e.getStatusCode().value() + ")"
                + (detail != null ? ": " + detail : ""), e);
    }

    private static ProvisioningException unreachable(String baseUrl, RestClientException e) {
        return new ProvisioningException("Could not reach GitLab at " + baseUrl + ": " + e.getMessage(), e);
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

    /**
     * @param id        kept as the key's provenance, so a later rotation could
     *                  revoke or rotate this exact token
     * @param expiresAt what GitLab actually set, which wins over what was asked
     */
    public record CreatedToken(@JsonProperty("id") Long id, @JsonProperty("token") String token,
            @JsonProperty("expires_at") String expiresAt) {
    }
}
