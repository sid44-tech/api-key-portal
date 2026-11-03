package io.github.apikeyportal.crypto;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The key, or keys, values are encrypted with.
 * <p>
 * Two shapes, and the simple one is still the default:
 * <ul>
 *   <li><b>One key.</b> Set {@code portal.encryption.key} and nothing else.
 *       Values are written exactly as they always were, with no key id, and an
 *       install that never rotates never sees any of the machinery below.</li>
 *   <li><b>Named keys.</b> Set {@code portal.encryption.keys.<id>} once per key
 *       and {@code portal.encryption.active-key} to the id new writes should
 *       use. Every value then carries the id of the key that encrypted it, so
 *       the old key can stay configured - and keep old values readable - while
 *       the re-encrypt command works through them.</li>
 * </ul>
 * The single key stays readable in named-key mode: it is simply the key with no
 * id, so adding named keys to a running portal needs no migration and no
 * downtime.
 */
@ConfigurationProperties(prefix = "portal.encryption")
@Validated
public class EncryptionProperties {

    /**
     * Base64 encoded 256-bit AES key used to encrypt key values at rest. The
     * only key a portal that never rotates ever needs.
     */
    private final String key;

    /**
     * Additional keys by id, e.g. {@code portal.encryption.keys.2026a}. Ids are
     * written into every value encrypted with them, so they are permanent:
     * renaming one orphans its data.
     */
    private final Map<String, String> keys;

    /**
     * Which of {@link #getKeys()} new writes use. Required once keys are named,
     * and must be one of them.
     */
    private final String activeKey;

    public EncryptionProperties(String key, @DefaultValue Map<String, String> keys, String activeKey) {
        this.key = key;
        this.keys = keys == null ? Map.of() : new LinkedHashMap<>(keys);
        this.activeKey = activeKey == null || activeKey.isBlank() ? null : activeKey.trim();
    }

    public String getKey() {
        return key;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public String getActiveKey() {
        return activeKey;
    }
}
