package com.sidbhat.apikeyportal.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the expiry sweep once a day. Kept apart from the service so the service
 * can be driven directly in tests and on demand without a clock involved.
 */
@Component
@ConditionalOnProperty(prefix = "portal.notifications", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ExpiryReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReminderScheduler.class);

    private final ExpiryReminderService service;

    public ExpiryReminderScheduler(ExpiryReminderService service) {
        this.service = service;
    }

    @Scheduled(cron = "${portal.notifications.cron}")
    public void sendDueReminders() {
        try {
            service.sendDueReminders();
        } catch (RuntimeException e) {
            // a scheduled method that throws is silently dropped by the scheduler;
            // say so, or a broken sweep looks like a quiet one
            log.error("The expiry reminder sweep failed", e);
        }
    }
}
