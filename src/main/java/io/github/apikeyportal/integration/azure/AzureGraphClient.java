package io.github.apikeyportal.integration.azure;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.apikeyportal.integration.ProvisioningException;

/**
 * Minimal Microsoft Graph client for the operations the portal needs: acquiring an
 * app-only token and creating an app registration with a client secret.
 * <p>
 * Three REST calls didn't seem worth the Azure SDK dependency tree. All failures
 * are translated into {@link ProvisioningException}s with messages that are safe
 * and useful to show to the user.
 */
@Component
public class AzureGraphClient {

    static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    private static final Pattern ERROR_DESCRIPTION = Pattern.compile("\"error_description\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final int MAX_DETAIL_LENGTH = 300;

    private final RestClient loginClient;
    private final RestClient graphClient;

    @Autowired
    public AzureGraphClient(AzureAdProperties properties) {
        this(defaultBuilder(), properties);
    }

    AzureGraphClient(RestClient.Builder builder, AzureAdProperties properties) {
        this.loginClient = builder.clone().baseUrl(properties.loginBaseUrl()).build();
        this.graphClient = builder.clone().baseUrl(properties.graphBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * Acquires an app-only Graph token via the OAuth2 client credentials flow.
     * Also serves as the connection test for stored integration credentials.
     */
    public String acquireToken(String tenantId, String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", GRAPH_SCOPE);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            TokenResponse response = loginClient.post()
                    .uri("/{tenantId}/oauth2/v2.0/token", tenantId)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new ProvisioningException("Azure AD returned an empty token response");
            }
            return response.accessToken();
        } catch (RestClientResponseException e) {
            throw translate("Azure AD rejected the credentials", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Creates a new single-tenant app registration. The caller (the portal's own app
     * registration) is automatically added as owner, which is what allows managing it
     * later with only the {@code Application.ReadWrite.OwnedBy} permission.
     */
    public CreatedApplication createApplication(String token, String displayName) {
        try {
            CreatedApplication created = graphClient.post()
                    .uri("/v1.0/applications")
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "displayName", displayName,
                            "signInAudience", "AzureADMyOrg",
                            "notes", "Created by API Key Portal"))
                    .retrieve()
                    .body(CreatedApplication.class);
            if (created == null || created.objectId() == null || created.clientId() == null) {
                throw new ProvisioningException("Azure AD returned an unexpected response when creating the application");
            }
            return created;
        } catch (RestClientResponseException e) {
            throw translate("Could not create the app registration", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Creates the service principal for an app registration in the tenant. Without it
     * the new client credentials can't actually sign in. The Azure portal does this
     * implicitly; the Graph API does not.
     */
    public void createServicePrincipal(String token, String clientId) {
        try {
            graphClient.post()
                    .uri("/v1.0/servicePrincipals")
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("appId", clientId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate("Could not create the service principal", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Adds a client secret to an app registration. Graph generates the secret value and
     * returns it exactly once, in this response.
     */
    public CreatedSecret addPassword(String token, String applicationObjectId, String displayName, Instant endDateTime) {
        try {
            CreatedSecret created = graphClient.post()
                    .uri("/v1.0/applications/{id}/addPassword", applicationObjectId)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("passwordCredential", Map.of(
                            "displayName", displayName,
                            "endDateTime", endDateTime.toString())))
                    .retrieve()
                    .body(CreatedSecret.class);
            if (created == null || created.secret() == null || created.secret().isBlank()) {
                throw new ProvisioningException("Azure AD returned an unexpected response when creating the client secret");
            }
            return created;
        } catch (RestClientResponseException e) {
            throw translate("Could not create the client secret", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Deletes an app registration. Used to clean up after a partially failed
     * provisioning run; allowed under {@code Application.ReadWrite.OwnedBy} because
     * the portal owns what it created. Azure soft-deletes it (recoverable for 30 days).
     */
    /**
     * Removes one secret from an app registration, leaving the registration and
     * its other secrets alone. This is the tidy-up after a rotation: Azure has
     * already stopped the secret working on its end date, and this takes the
     * dead entry off the list.
     *
     * @return true if it was removed, false if Azure no longer had it - either
     *         way it is gone, which is the point
     */
    public boolean removePassword(String token, String applicationObjectId, String keyId) {
        try {
            graphClient.post()
                    .uri("/v1.0/applications/{id}/removePassword", applicationObjectId)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("keyId", keyId))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            // 404 for a registration that is gone; Graph answers 400 for a keyId it has no secret for
            String body = e.getResponseBodyAsString();
            if (e.getStatusCode().value() == 404 || (e.getStatusCode().value() == 400 && body != null
                    && body.toLowerCase(java.util.Locale.ROOT).contains("no password credential"))) {
                return false;
            }
            throw translate("Could not remove the client secret", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * The key ids of secrets on an app registration that carry {@code displayName}
     * and have already expired. For keys minted before the portal recorded each
     * secret's id: an expired secret is inert whichever one it is, so matching by
     * name among the expired ones is safe where matching among live ones would not be.
     */
    public java.util.List<String> expiredPasswordCredentials(String token, String applicationObjectId,
            String displayName, Instant now) {
        try {
            ApplicationPasswords application = graphClient.get()
                    .uri("/v1.0/applications/{id}?$select=passwordCredentials", applicationObjectId)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .body(ApplicationPasswords.class);
            if (application == null || application.passwordCredentials() == null) {
                return java.util.List.of();
            }
            return application.passwordCredentials().stream()
                    .filter(credential -> credential.keyId() != null && displayName.equals(credential.displayName()))
                    .filter(credential -> credential.endDateTime() != null
                            && !java.time.OffsetDateTime.parse(credential.endDateTime()).toInstant().isAfter(now))
                    .map(PasswordCredential::keyId)
                    .toList();
        } catch (RestClientResponseException e) {
            throw translate("Could not read the app registration's secrets", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    record ApplicationPasswords(@JsonProperty("passwordCredentials") java.util.List<PasswordCredential> passwordCredentials) {
    }

    record PasswordCredential(@JsonProperty("keyId") String keyId, @JsonProperty("displayName") String displayName,
            @JsonProperty("endDateTime") String endDateTime) {
    }

    public void deleteApplication(String token, String applicationObjectId) {
        try {
            graphClient.delete()
                    .uri("/v1.0/applications/{id}", applicationObjectId)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate("Could not delete the app registration", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    private static ProvisioningException translate(String action, RestClientResponseException e) {
        String detail = extractDetail(e.getResponseBodyAsString());
        String message = action + " (HTTP " + e.getStatusCode().value() + ")"
                + (detail != null ? ": " + detail : "");
        return new ProvisioningException(message, e);
    }

    private static ProvisioningException unreachable(RestClientException e) {
        return new ProvisioningException("Could not reach Azure AD: " + e.getMessage(), e);
    }

    /**
     * Best-effort extraction of the human-readable part of an Azure error response,
     * e.g. {@code error_description} from the token endpoint or {@code error.message}
     * from Graph. Falls back to nothing rather than dumping raw JSON at the user.
     */
    private static String extractDetail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        for (Pattern pattern : new Pattern[] {ERROR_DESCRIPTION, ERROR_MESSAGE}) {
            Matcher matcher = pattern.matcher(body);
            if (matcher.find() && !matcher.group(1).isBlank()) {
                String detail = matcher.group(1);
                return detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) + "…" : detail;
            }
        }
        return null;
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    /**
     * @param objectId Graph object id of the application, used for follow-up calls
     * @param clientId the application (client) ID used to authenticate with the credential
     */
    public record CreatedApplication(@JsonProperty("id") String objectId, @JsonProperty("appId") String clientId) {
    }

    /**
     * @param secret the generated secret value; shown by Azure exactly once, in this response
     * @param endDateTime ISO-8601 expiry as returned by Graph
     */
    public record CreatedSecret(@JsonProperty("secretText") String secret,
            @JsonProperty("endDateTime") String endDateTime, @JsonProperty("keyId") String keyId) {

        /** Without the key id, for callers and tests that have no use for it. */
        public CreatedSecret(String secret, String endDateTime) {
            this(secret, endDateTime, null);
        }
    }
}
