package io.github.apikeyportal.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EncryptionServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final EncryptionService service = new EncryptionService(new EncryptionProperties(KEY, null, null));

    @Test
    void roundTripsValue() {
        String encrypted = service.encrypt("sk_test_123");

        assertThat(encrypted).isNotEqualTo("sk_test_123");
        assertThat(service.decrypt(encrypted)).isEqualTo("sk_test_123");
    }

    @Test
    void roundTripsUnicode() {
        assertThat(service.decrypt(service.encrypt("clé-🔑"))).isEqualTo("clé-🔑");
    }

    @Test
    void usesRandomIvSoSameInputEncryptsDifferently() {
        assertThat(service.encrypt("same")).isNotEqualTo(service.encrypt("same"));
    }

    @Test
    void passesNullThrough() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void rejectsTamperedCipherText() {
        byte[] bytes = Base64.getDecoder().decode(service.encrypt("sk_test_123"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> service.decrypt(Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void cannotDecryptWithDifferentKey() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        EncryptionService other = new EncryptionService(
                new EncryptionProperties(Base64.getEncoder().encodeToString(otherKey), null, null));

        assertThatThrownBy(() -> other.decrypt(service.encrypt("sk_test_123")))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void rejectsKeysThatAreNot256Bits() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new EncryptionService(new EncryptionProperties(shortKey, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- key rotation ----------

    private static String freshKey() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void asingleUnnamedKeyWritesExactlyWhatItAlwaysDid() {
        String encrypted = service.encrypt("ghp_secret");

        // no prefix: an install that never rotates sees no change on disk, and
        // values written by older versions of the portal stay readable
        assertThat(encrypted).doesNotContain("kp1:");
        assertThat(EncryptionService.keyIdOf(encrypted)).isEqualTo(EncryptionService.LEGACY_KEY_ID);
        assertThat(service.decrypt(encrypted)).isEqualTo("ghp_secret");
    }

    @Test
    void namedKeysStampTheirIdOnWhatTheyWrite() {
        String newKey = freshKey();
        EncryptionService rotating = new EncryptionService(
                new EncryptionProperties(KEY, Map.of("2026a", newKey), "2026a"));

        String encrypted = rotating.encrypt("ghp_secret");

        assertThat(encrypted).startsWith("kp1:2026a:");
        assertThat(EncryptionService.keyIdOf(encrypted)).isEqualTo("2026a");
        assertThat(rotating.decrypt(encrypted)).isEqualTo("ghp_secret");
        assertThat(rotating.getActiveKeyId()).isEqualTo("2026a");
    }

    @Test
    void theOldKeyKeepsOpeningOldValuesWhileTheNewOneTakesWrites() {
        String legacyValue = service.encrypt("written-before-rotation");

        EncryptionService rotating = new EncryptionService(
                new EncryptionProperties(KEY, Map.of("2026a", freshKey()), "2026a"));

        // this is what makes rotation possible with the portal still running
        assertThat(rotating.decrypt(legacyValue)).isEqualTo("written-before-rotation");
        assertThat(rotating.isOnActiveKey(legacyValue)).isFalse();
        assertThat(rotating.isOnActiveKey(rotating.encrypt("new"))).isTrue();
    }

    @Test
    void aValueWhoseKeyIsNoLongerConfiguredFailsLoudlyRatherThanQuietly() {
        EncryptionService rotating = new EncryptionService(
                new EncryptionProperties(KEY, Map.of("2026a", freshKey()), "2026a"));
        String written = rotating.encrypt("secret");

        // the operator dropped 2026a from configuration too early
        EncryptionService without = new EncryptionService(new EncryptionProperties(KEY, null, null));

        assertThatThrownBy(() -> without.decrypt(written))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("No encryption key '2026a' is configured");
    }

    @Test
    void anActiveKeyThatIsNotConfiguredIsRefusedAtStartup() {
        assertThatThrownBy(() -> new EncryptionService(
                new EncryptionProperties(KEY, Map.of("2026a", freshKey()), "typo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a configured key");
    }

    @Test
    void namedKeysWithoutAnActiveOneAreRefusedRatherThanGuessed() {
        assertThatThrownBy(() -> new EncryptionService(
                new EncryptionProperties(KEY, Map.of("2026a", freshKey()), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active-key must say");
    }

    @Test
    void aKeyIdThatCouldNotBeParsedBackOutOfAValueIsRefused() {
        assertThatThrownBy(() -> new EncryptionService(
                new EncryptionProperties(KEY, Map.of("bad:id", freshKey()), "bad:id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a usable key id");
        assertThatThrownBy(() -> new EncryptionService(
                new EncryptionProperties(KEY, Map.of("legacy", freshKey()), "legacy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void thePortalRefusesToStartWithNoKeyAtAll() {
        assertThatThrownBy(() -> new EncryptionService(new EncryptionProperties(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No encryption key configured");
    }
}
