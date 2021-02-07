package com.sidbhat.apikeyportal.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class EncryptionServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final EncryptionService service = new EncryptionService(new EncryptionProperties(KEY));

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
                new EncryptionProperties(Base64.getEncoder().encodeToString(otherKey)));

        assertThatThrownBy(() -> other.decrypt(service.encrypt("sk_test_123")))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void rejectsKeysThatAreNot256Bits() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new EncryptionService(new EncryptionProperties(shortKey)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
