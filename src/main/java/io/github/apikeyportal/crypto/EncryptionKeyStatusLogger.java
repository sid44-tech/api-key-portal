package io.github.apikeyportal.crypto;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Says at startup whether a key rotation is finished.
 * <p>
 * A rotation that was started and forgotten is the dangerous state: everything
 * works, so nobody notices, until the old key is deleted from configuration
 * months later and the values it still holds become unreadable. One line at
 * startup is what turns that into something somebody can see.
 */
@Component
@ConditionalOnProperty(prefix = "portal.encryption", name = "reencrypt", havingValue = "false",
        matchIfMissing = true)
public class EncryptionKeyStatusLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyStatusLogger.class);

    private final ReencryptService service;
    private final EncryptionService encryption;

    public EncryptionKeyStatusLogger(ReencryptService service, EncryptionService encryption) {
        this.service = service;
        this.encryption = encryption;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Integer> counts = service.countByKeyId();
        int stale = counts.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(encryption.getActiveKeyId()))
                .mapToInt(Map.Entry::getValue)
                .sum();
        if (stale == 0) {
            log.info("Encryption: all stored values are on the active key '{}'", encryption.getActiveKeyId());
            return;
        }
        log.warn("Encryption: {} stored value(s) are still on an older key (counts by key: {}). "
                        + "Keep those keys configured, and finish the rotation with "
                        + "--portal.encryption.reencrypt=true",
                stale, counts);
    }
}
