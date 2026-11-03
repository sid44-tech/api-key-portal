package io.github.apikeyportal.sso;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Whether the identity provider can be reached, by reading its OpenID discovery
 * document - the first thing a sign-in needs. Only when SSO is on: a portal
 * that does not use it has nothing to check and reports as much.
 * <p>
 * This is an external dependency, so it is part of the deep probe and
 * deliberately not of readiness: an identity provider having a bad morning
 * means nobody new can sign in, not that this instance should be taken out of
 * rotation.
 */
@Component("sso")
public class SsoHealthIndicator implements HealthIndicator {

    private final SsoService sso;
    private final RestClient restClient;

    @Autowired
    public SsoHealthIndicator(SsoService sso) {
        this(sso, defaultBuilder());
    }

    SsoHealthIndicator(SsoService sso, RestClient.Builder builder) {
        this.sso = sso;
        this.restClient = builder.clone().build();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        // a probe must answer quickly, or the thing probing it gives up first
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Override
    public Health health() {
        SsoConfiguration config = sso.find().filter(SsoConfiguration::isEnabled).orElse(null);
        if (config == null) {
            return Health.up().withDetail("enabled", false).build();
        }
        String issuer = config.getIssuerUri();
        String discovery = (issuer.endsWith("/") ? issuer : issuer + "/") + ".well-known/openid-configuration";
        try {
            restClient.get().uri(discovery).retrieve().toBodilessEntity();
            return Health.up().withDetail("enabled", true).withDetail("issuer", issuer).build();
        } catch (RestClientException e) {
            return Health.down().withDetail("enabled", true).withDetail("issuer", issuer)
                    .withDetail("error", e.getMessage()).build();
        }
    }
}
