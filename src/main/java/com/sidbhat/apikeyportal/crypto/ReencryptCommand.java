package com.sidbhat.apikeyportal.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * The re-encrypt command:
 * <pre>
 * java -jar api-key-portal.jar --portal.encryption.reencrypt=true
 * </pre>
 * Runs the rewrite and then stops the application, rather than going on to
 * serve traffic - this is a maintenance task, and leaving a half-purposed
 * instance listening afterwards would invite someone to use it.
 * <p>
 * The portal stays usable throughout a rotation without this: values carry the
 * id of the key that wrote them, so the old key keeps opening old values while
 * new writes go to the new one. This command just finishes the job so the old
 * key can be retired.
 */
@Component
@ConditionalOnProperty(prefix = "portal.encryption", name = "reencrypt", havingValue = "true")
public class ReencryptCommand implements ApplicationRunner, ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ReencryptCommand.class);

    private final ReencryptService service;
    private final EncryptionService encryption;
    private final ApplicationContext context;

    private int exitCode;

    public ReencryptCommand(ReencryptService service, EncryptionService encryption, ApplicationContext context) {
        this.service = service;
        this.encryption = encryption;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Re-encrypt command: active key is '{}'; values per key before the run: {}",
                encryption.getActiveKeyId(), service.countByKeyId());
        try {
            ReencryptService.Result result = service.reencryptAll("reencrypt-command");
            log.info("Done. {} of {} value(s) rewritten. Every stored value is now on key '{}' - "
                            + "the previous key can be removed from configuration.",
                    result.rewritten(), result.examined(), encryption.getActiveKeyId());
        } catch (RuntimeException e) {
            // nothing was changed; the transaction rolled back
            log.error("Re-encryption failed, and nothing was changed: {}", e.getMessage());
            exitCode = 1;
        }
    }

    /** Stops once the work is done, with a non-zero status if it did not finish. */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        System.exit(org.springframework.boot.SpringApplication.exit(context, () -> exitCode));
    }
}
