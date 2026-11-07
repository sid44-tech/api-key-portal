package io.github.apikeyportal.integration.twilio;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.apikeyportal.integration.ProvisioningException;

/**
 * The slice of the Twilio REST API this portal needs: prove a credential can
 * manage API keys, mint a Standard API key, and delete one.
 * <p>
 * The portal authenticates with a <em>Main</em> API key (or the account's Auth
 * Token): Twilio does not let Standard keys manage other keys. Every key the
 * portal mints is a Standard key, so what it hands out can never mint more.
 */
@Component
public class TwilioClient {

    /** Twilio's limit on a key's friendly name. */
    static final int MAX_FRIENDLY_NAME = 64;

    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");
    private static final int MAX_DETAIL_LENGTH = 300;

    private final RestClient restClient;

    @Autowired
    public TwilioClient(TwilioProperties properties) {
        this(defaultBuilder(), properties);
    }

    TwilioClient(RestClient.Builder builder, TwilioProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * The connection test: lists the account's API keys. That proves the
     * account, the credential, and - the part a Standard key would fail - that
     * the credential is allowed to manage keys at all.
     *
     * @param username the Main API key's SID, or the Account SID with the Auth Token
     */
    public void verifyKeyAccess(String accountSid, String username, String secret) {
        try {
            restClient.get()
                    .uri(keysPath(accountSid) + ".json?PageSize=1")
                    .header("Authorization", basic(username, secret))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw translate("Twilio rejected the credentials", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Mints a Standard API key. The secret is in this response and nowhere
     * else, ever again.
     */
    public CreatedKey createKey(String accountSid, String username, String secret, String friendlyName) {
        String name = friendlyName.length() > MAX_FRIENDLY_NAME
                ? friendlyName.substring(0, MAX_FRIENDLY_NAME) : friendlyName;
        try {
            CreatedKey created = restClient.post()
                    .uri(keysPath(accountSid) + ".json")
                    .header("Authorization", basic(username, secret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("FriendlyName=" + URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .retrieve()
                    .body(CreatedKey.class);
            if (created == null || created.sid() == null || created.secret() == null || created.secret().isBlank()) {
                throw new ProvisioningException("Twilio's response did not include the key's secret");
            }
            return created;
        } catch (RestClientResponseException e) {
            throw translate("Could not create the API key", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Deletes an API key; anything using it stops working at once.
     *
     * @return false if Twilio no longer had the key - somebody deleted it first
     */
    public boolean deleteKey(String accountSid, String username, String secret, String keySid) {
        try {
            restClient.delete()
                    .uri(keysPath(accountSid) + "/" + URLEncoder.encode(keySid, StandardCharsets.UTF_8) + ".json")
                    .header("Authorization", basic(username, secret))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return false;
            }
            throw translate("Could not delete the API key", e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    private static String keysPath(String accountSid) {
        return "/2010-04-01/Accounts/" + URLEncoder.encode(accountSid.trim(), StandardCharsets.UTF_8) + "/Keys";
    }

    private static String basic(String username, String secret) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static ProvisioningException translate(String action, RestClientResponseException e) {
        String detail = extractDetail(e.getResponseBodyAsString());
        return new ProvisioningException(action + " (HTTP " + e.getStatusCode().value() + ")"
                + (detail != null ? ": " + detail : ""), e);
    }

    private static ProvisioningException unreachable(RestClientException e) {
        return new ProvisioningException("Could not reach Twilio: " + e.getMessage(), e);
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
     * @param sid    the key's SID ({@code SK...}): its public half, and what a
     *               deletion targets
     * @param secret the private half, shown by Twilio this once
     */
    public record CreatedKey(@JsonProperty("sid") String sid, @JsonProperty("secret") String secret) {
    }
}
