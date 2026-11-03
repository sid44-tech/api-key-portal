package com.sidbhat.apikeyportal.rotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the rotation sweep once a day, by default an hour before the expiry
 * reminder sweep: a key that is going to be replaced should already have been by
 * the time reminders are considered, so its owner gets the one email that says
 * "a replacement is ready" rather than that plus a plain warning.
 * <p>
 * The same run then deletes, at the provider, any expired key whose replacement
 * exists and whose provider would otherwise leave it working for ever.
 * <p>
 * Kept apart from the service so the service can be driven directly in tests
 * and on demand without a clock involved.
 */
@Component
@ConditionalOnProperty(prefix = "portal.rotation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class KeyRotationScheduler {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationScheduler.class);

    private final KeyRotationService service;

    public KeyRotationScheduler(KeyRotationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${portal.rotation.cron}")
    public void rotateDueKeys() {
        try {
            service.rotateDueKeys();
        } catch (RuntimeException e) {
            // a scheduled method that throws is silently dropped by the scheduler;
            // say so, or a broken sweep looks like a quiet one
            log.error("The key rotation sweep failed", e);
        }
        try {
            service.revokeExpiredKeys();
        } catch (RuntimeException e) {
            log.error("The expired-key deletion sweep failed", e);
        }
    }
}
