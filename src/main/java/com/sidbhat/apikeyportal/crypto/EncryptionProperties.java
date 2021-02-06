package com.sidbhat.apikeyportal.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
@ConfigurationProperties(prefix = "portal.encryption")
public class EncryptionProperties {

    /**
     * Base64 encoded 256-bit AES key used to encrypt key values at rest.
     */
    private final String key;

    public EncryptionProperties(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
