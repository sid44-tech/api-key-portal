package com.sidbhat.apikeyportal.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Encrypts and decrypts strings with AES-256 in GCM mode, under one of possibly
 * several keys.
 * <p>
 * Two stored formats, and which one is written depends only on configuration:
 * <ul>
 *   <li>{@code base64(iv || ciphertext+tag)} - what the portal has always
 *       written, and what it still writes when a single unnamed key is
 *       configured. Read as belonging to that key.</li>
 *   <li>{@code kp1:<keyId>:base64(iv || ciphertext+tag)} - written once keys are
 *       named, so every value says which key opens it.</li>
 * </ul>
 * That is what makes rotation possible without downtime: the old key stays
 * configured and keeps opening old values while new writes go to the new key,
 * and {@code ReencryptCommand} moves the backlog across at leisure. A key may
 * only be retired from configuration once nothing references it.
 * <p>
 * A fresh random IV is generated for every call to {@link #encrypt(String)}.
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    /** Marks the key-id format. Versioned so a third format could follow it. */
    static final String PREFIX = "kp1:";

    /** The id of the original, unnamed key. Never written into a value. */
    public static final String LEGACY_KEY_ID = "legacy";

    /** Ids go into stored values, so keep them to something unambiguous. */
    private static final Pattern VALID_KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Map<String, SecretKey> keysById = new LinkedHashMap<>();
    private final String activeKeyId;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptionService(EncryptionProperties properties) {
        if (properties.getKey() != null && !properties.getKey().isBlank()) {
            keysById.put(LEGACY_KEY_ID, parseKey(properties.getKey(), "portal.encryption.key"));
        }
        properties.getKeys().forEach((id, value) -> {
            if (!VALID_KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("portal.encryption.keys: '" + id
                        + "' is not a usable key id - letters, digits, underscore and dash only");
            }
            if (LEGACY_KEY_ID.equals(id)) {
                throw new IllegalArgumentException("portal.encryption.keys: '" + LEGACY_KEY_ID
                        + "' is reserved for portal.encryption.key");
            }
            keysById.put(id, parseKey(value, "portal.encryption.keys." + id));
        });

        if (keysById.isEmpty()) {
            throw new IllegalArgumentException("No encryption key configured. Set PORTAL_ENCRYPTION_KEY "
                    + "(or portal.encryption.keys.<id> plus portal.encryption.active-key).");
        }
        this.activeKeyId = resolveActiveKey(properties);
        log.info("Encryption ready: {} key(s) configured [{}], new values written with '{}'",
                keysById.size(), String.join(", ", keysById.keySet()), activeKeyId);
    }

    /**
     * Which key new writes use. With no named keys this is the unnamed one, and
     * the portal behaves exactly as it did before rotation existed.
     */
    private String resolveActiveKey(EncryptionProperties properties) {
        String configured = properties.getActiveKey();
        if (configured == null) {
            if (properties.getKeys().isEmpty()) {
                return LEGACY_KEY_ID;
            }
            throw new IllegalArgumentException("portal.encryption.active-key must say which of the named "
                    + "keys new values are encrypted with: " + String.join(", ", properties.getKeys().keySet()));
        }
        if (!keysById.containsKey(configured)) {
            throw new IllegalArgumentException("portal.encryption.active-key is '" + configured
                    + "', which is not a configured key. Configured: " + String.join(", ", keysById.keySet()));
        }
        return configured;
    }

    private static SecretKey parseKey(String base64, String source) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + " must be a base64 encoded 256-bit key", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(source + " must be a base64 encoded 256-bit key");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** The id new values are encrypted under. */
    public String getActiveKeyId() {
        return activeKeyId;
    }

    /** Every key id this portal can currently decrypt with. */
    public Map<String, SecretKey> getConfiguredKeys() {
        return Map.copyOf(keysById);
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        String body = encryptWith(keysById.get(activeKeyId), plainText);
        // the unnamed key keeps writing the original format, so an install that
        // never rotates never grows a prefix it did not ask for
        return LEGACY_KEY_ID.equals(activeKeyId) ? body : PREFIX + activeKeyId + ':' + body;
    }

    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        String keyId = keyIdOf(encoded);
        String body = encoded.startsWith(PREFIX) ? encoded.substring(PREFIX.length() + keyId.length() + 1) : encoded;
        SecretKey key = keysById.get(keyId);
        if (key == null) {
            log.error("A stored value is encrypted with key '{}', which is not configured. Keep that key "
                    + "configured until the re-encrypt command has moved every value off it.", keyId);
            throw new EncryptionException("No encryption key '" + keyId + "' is configured");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(body));
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException e) {
            // Almost always one thing: the configured key is not the key this
            // value was written with. Worth an error rather than a quiet
            // exception - the portal will otherwise only mention it one key at a
            // time, to whoever happened to click reveal.
            log.error("Could not decrypt a stored value with key '{}'. Is it the key this data was "
                    + "encrypted with?", keyId, e);
            throw new EncryptionException("Unable to decrypt value", e);
        }
    }

    /**
     * Which key a stored value was encrypted with, without decrypting it. Values
     * written before rotation existed carry no id and belong to the unnamed key.
     */
    public static String keyIdOf(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return LEGACY_KEY_ID;
        }
        int end = encoded.indexOf(':', PREFIX.length());
        if (end < 0) {
            // prefixed but malformed; treat as legacy and let decryption fail loudly
            return LEGACY_KEY_ID;
        }
        return encoded.substring(PREFIX.length(), end);
    }

    /** True if this value is already encrypted with the key new writes use. */
    public boolean isOnActiveKey(String encoded) {
        return activeKeyId.equals(keyIdOf(encoded));
    }

    private String encryptWith(SecretKey key, String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + cipherText.length)
                    .put(iv)
                    .put(cipherText)
                    .array());
        } catch (GeneralSecurityException e) {
            // no plaintext and no key material in the message, only that it failed
            log.error("Could not encrypt a value; the key value was not stored", e);
            throw new EncryptionException("Unable to encrypt value", e);
        }
    }
}
