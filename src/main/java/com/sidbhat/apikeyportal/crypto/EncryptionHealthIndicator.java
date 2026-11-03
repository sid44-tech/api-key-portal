package com.sidbhat.apikeyportal.crypto;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Proves the encryption key works, not merely that one is configured: a value
 * is encrypted and decrypted again on every probe. A portal whose key is wrong
 * can serve every page and fail only at the moment someone reveals a key, so
 * the readiness probe includes this and a load balancer learns before a person does.
 */
@Component("encryption")
public class EncryptionHealthIndicator implements HealthIndicator {

    private static final String PROBE = "health-probe";

    private final EncryptionService encryption;

    public EncryptionHealthIndicator(EncryptionService encryption) {
        this.encryption = encryption;
    }

    @Override
    public Health health() {
        try {
            String roundTrip = encryption.decrypt(encryption.encrypt(PROBE));
            if (!PROBE.equals(roundTrip)) {
                return Health.down().withDetail("error", "a value did not survive an encrypt/decrypt round trip").build();
            }
            return Health.up()
                    .withDetail("activeKey", encryption.getActiveKeyId() == null ? "default" : encryption.getActiveKeyId())
                    .withDetail("configuredKeys", encryption.getConfiguredKeys().size())
                    .build();
        } catch (RuntimeException e) {
            // the message, never key material
            return Health.down().withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
    }
}
