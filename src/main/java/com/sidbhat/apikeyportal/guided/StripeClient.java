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

import com.fasterxml.jackson.annotation.JsonProperty;

import com.sidbhat.apikeyportal.integration.ProvisioningException;

/**
 * Verifies a Stripe API key by asking which account it belongs to.
 * <p>
 * Restricted keys are the ones worth encouraging, and they may well lack
 * permission to read the account - so a 403 is treated as a pass. It still
 * proves the key is real and enabled, which is all verification claims; only a
 * 401 means Stripe does not know this key.
 */
@Component
public class StripeClient {

    private final RestClient client;

    @Autowired
    public StripeClient(StripeProperties properties) {
        this(defaultBuilder(), properties);
    }

    StripeClient(RestClient.Builder builder, StripeProperties properties) {
        this.client = builder.clone().baseUrl(properties.apiBaseUrl()).build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * @return what Stripe says about the account, or an empty result for a key
     *         that works but may not read the account
     * @throws ProvisioningException if Stripe rejects the key or is unreachable
     */
    public AccountInfo verifyKey(String apiKey) {
        try {
            AccountInfo info = client.get()
                    .uri("/v1/account")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(AccountInfo.class);
            return info == null ? new AccountInfo(null, null, null) : info;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403) {
                // a real key without the Account permission; the restricted keys
                // this walk-through recommends often look exactly like this
                return new AccountInfo(null, null, null);
            }
            if (e.getStatusCode().value() == 401) {
                throw new ProvisioningException(
                        "Stripe did not accept the key (HTTP 401). Check it was copied completely "
                        + "and hasn't been revoked or rolled in the dashboard.", e);
            }
            throw new ProvisioningException("Stripe rejected the key (HTTP "
                    + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new ProvisioningException("Could not reach Stripe: " + e.getMessage(), e);
        }
    }

    /**
     * @param livemode null where the key could not read the account. Worth
     *                 recording: a test key and a live key look alike in a list
     *                 but are very different things to leak.
     */
    public record AccountInfo(@JsonProperty("id") String id,
            @JsonProperty("business_profile") BusinessProfile businessProfile,
            @JsonProperty("livemode") Boolean livemode) {
    }

    public record BusinessProfile(@JsonProperty("name") String name) {
    }

    /** Overridable so tests can point at a stub. */
    @ConfigurationProperties(prefix = "portal.stripe")
    public record StripeProperties(@DefaultValue("https://api.stripe.com") String apiBaseUrl) {
    }
}
