package io.github.apikeyportal.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class EncryptionHealthIndicatorTest {

    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void aWorkingKeyRoundTripsAndReportsWhichKeyIsActive() {
        Health health = new EncryptionHealthIndicator(new EncryptionService(new EncryptionProperties(KEY,
                Map.of("2026a", KEY), "2026a"))).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("activeKey", "2026a").containsEntry("configuredKeys", 2);
        // never the key itself
        assertThat(health.getDetails().toString()).doesNotContain(KEY);
    }

    @Test
    void aKeyThatCannotBeUsedIsDownWithAMessageAndNoKeyMaterial() {
        EncryptionService broken = new EncryptionService(new EncryptionProperties(KEY, null, null)) {
            @Override
            public String encrypt(String plainText) {
                throw new EncryptionException("Encryption failed", null);
            }
        };

        Health health = new EncryptionHealthIndicator(broken).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(String.valueOf(health.getDetails().get("error"))).contains("Encryption failed").doesNotContain(KEY);
    }
}
