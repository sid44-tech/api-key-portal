package com.sidbhat.apikeyportal.crypto;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "portal.encryption")
@Validated
public class EncryptionProperties {

    /**
     * Base64 encoded 256-bit AES key used to encrypt key values at rest.
     */
    @NotBlank(message = "must be set, e.g. with the PORTAL_ENCRYPTION_KEY environment variable")
    private final String key;

    public EncryptionProperties(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
