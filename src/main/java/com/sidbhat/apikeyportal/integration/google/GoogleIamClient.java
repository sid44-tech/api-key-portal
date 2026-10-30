package com.sidbhat.apikeyportal.integration.google;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
import com.sidbhat.apikeyportal.integration.ProvisioningException;

/**
 * Minimal Google Cloud IAM client for the operations the portal needs: acquiring an
 * access token for a service account and creating a new service account with a JSON key.
 * <p>
 * Mirrors {@link com.sidbhat.apikeyportal.integration.azure.AzureGraphClient}: a few
 * REST calls aren't worth the Google SDK dependency tree, and all failures are
 * translated into {@link ProvisioningException}s safe to show to the user.
 */
@Component
public class GoogleIamClient {

    static final String IAM_SCOPE = "https://www.googleapis.com/auth/iam";
    static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR_DESCRIPTION = Pattern.compile("\"error_description\"\\s*:\\s*\"([^\"]*)\"");
    private static final int MAX_DETAIL_LENGTH = 300;

    private final RestClient tokenClient;
    private final RestClient iamClient;
    private final String tokenBaseUrl;

    @Autowired
    public GoogleIamClient(GoogleCloudProperties properties) {
        this(defaultBuilder(), properties);
    }

    GoogleIamClient(RestClient.Builder builder, GoogleCloudProperties properties) {
        this.tokenClient = builder.clone().baseUrl(properties.tokenBaseUrl()).build();
        this.iamClient = builder.clone().baseUrl(properties.iamBaseUrl()).build();
        this.tokenBaseUrl = properties.tokenBaseUrl();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * Acquires an access token for the portal's service account via the OAuth2
     * JWT-bearer flow (a self-signed RS256 assertion, exchanged at the token
     * endpoint). Also serves as the connection test for stored integration
     * credentials.
     *
     * @param clientEmail   the service account's email ({@code client_email} in the JSON key)
     * @param privateKeyPem the PKCS#8 PEM private key ({@code private_key} in the JSON key)
     */
    public String acquireToken(String clientEmail, String privateKeyPem) {
        String assertion = signedJwt(clientEmail, privateKeyPem);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", JWT_BEARER_GRANT);
        form.add("assertion", assertion);
        try {
            TokenResponse response = tokenClient.post()
                    .uri("/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new ProvisioningException("Google Cloud returned an empty token response");
            }
            return response.accessToken();
        } catch (RestClientResponseException e) {
            throw translate("Google Cloud rejected the credentials", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Creates a new service account in the project. The account ID must be 6-30
     * characters of {@code [a-z0-9-]}, starting with a letter.
     */
    public CreatedServiceAccount createServiceAccount(String token, String projectId, String accountId,
            String displayName) {
        try {
            CreatedServiceAccount created = iamClient.post()
                    .uri("/v1/projects/{projectId}/serviceAccounts", projectId)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "accountId", accountId,
                            "serviceAccount", Map.of(
                                    "displayName", displayName,
                                    "description", "Created by API Key Portal")))
                    .retrieve()
                    .body(CreatedServiceAccount.class);
            if (created == null || created.email() == null || created.email().isBlank()) {
                throw new ProvisioningException(
                        "Google Cloud returned an unexpected response when creating the service account");
            }
            return created;
        } catch (RestClientResponseException e) {
            throw translate("Could not create the service account", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Creates a JSON key for a service account. Google generates the private key and
     * returns it exactly once, base64 encoded in this response; the decoded JSON is
     * what clients authenticate with.
     */
    public String createServiceAccountKey(String token, String projectId, String serviceAccountEmail) {
        try {
            CreatedKey created = iamClient.post()
                    .uri("/v1/projects/{projectId}/serviceAccounts/{email}/keys", projectId, serviceAccountEmail)
                    .headers(h -> h.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("privateKeyType", "TYPE_GOOGLE_CREDENTIALS_FILE"))
                    .retrieve()
                    .body(CreatedKey.class);
            if (created == null || created.privateKeyData() == null || created.privateKeyData().isBlank()) {
                throw new ProvisioningException(
                        "Google Cloud returned an unexpected response when creating the service account key");
            }
            return new String(Base64.getDecoder().decode(created.privateKeyData()), StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            throw translate("Could not create the service account key", e);
        } catch (IllegalArgumentException e) {
            throw new ProvisioningException("Google Cloud returned a key that could not be decoded", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Deletes a service account. Used to clean up after a partially failed
     * provisioning run.
     */
    public void deleteServiceAccount(String token, String projectId, String serviceAccountEmail) {
        try {
            iamClient.delete()
                    .uri("/v1/projects/{projectId}/serviceAccounts/{email}", projectId, serviceAccountEmail)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate("Could not delete the service account", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /** Builds and signs the RS256 JWT assertion for the token exchange. */
    private String signedJwt(String clientEmail, String privateKeyPem) {
        Instant now = Instant.now();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"" + clientEmail + "\",\"scope\":\"" + IAM_SCOPE
                + "\",\"aud\":\"" + tokenBaseUrl + "/token\",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plusSeconds(600).getEpochSecond() + "}");
        String signingInput = header + "." + payload;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(parsePrivateKey(privateKeyPem));
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new ProvisioningException("Could not sign the authentication request with the stored private key. "
                    + "The key may be malformed - reconnect the integration with a fresh JSON key.", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("private key is not valid base64", e);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ProvisioningException translate(String action, RestClientResponseException e) {
        String detail = extractDetail(e.getResponseBodyAsString());
        String message = action + " (HTTP " + e.getStatusCode().value() + ")"
                + (detail != null ? ": " + detail : "");
        return new ProvisioningException(message, e);
    }

    private static ProvisioningException unreachable(RestClientException e) {
        return new ProvisioningException("Could not reach Google Cloud: " + e.getMessage(), e);
    }

    /**
     * Best-effort extraction of the human-readable part of a Google error response:
     * {@code error.message} from IAM or {@code error_description} from the token
     * endpoint. Falls back to nothing rather than dumping raw JSON at the user.
     */
    private static String extractDetail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        for (Pattern pattern : new Pattern[] {ERROR_MESSAGE, ERROR_DESCRIPTION}) {
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
     * @param email    the new service account's email, used to authenticate and for follow-up calls
     * @param uniqueId the account's immutable numeric ID
     */
    public record CreatedServiceAccount(@JsonProperty("email") String email,
            @JsonProperty("uniqueId") String uniqueId) {
    }

    record CreatedKey(@JsonProperty("privateKeyData") String privateKeyData) {
    }
}
