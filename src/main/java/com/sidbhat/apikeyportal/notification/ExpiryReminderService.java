package com.sidbhat.apikeyportal.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;

/**
 * The daily sweep: find keys inside a reminder threshold that haven't been
 * reminded at that threshold yet, email their owners, and record it.
 * <p>
 * Recording happens only after a successful send, so a mail outage means a
 * retry tomorrow rather than a reminder nobody received marked as sent.
 */
@Service
public class ExpiryReminderService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReminderService.class);

    private final ApiKeyRepository keys;
    private final ExpiryReminderRepository reminders;
    private final ExpiryReminderMailer mailer;

    public ExpiryReminderService(ApiKeyRepository keys, ExpiryReminderRepository reminders,
            ExpiryReminderMailer mailer) {
        this.keys = keys;
        this.reminders = reminders;
        this.mailer = mailer;
    }

    /** @return how many reminders went out */
    @Transactional
    public int sendDueReminders() {
        return sendDueReminders(LocalDate.now());
    }

    /** The date is a parameter so the sweep can be tested on any day it likes. */
    @Transactional
    public int sendDueReminders(LocalDate today) {
        if (!mailer.isAvailable()) {
            log.warn("No mail server configured; expiry reminders cannot be sent. "
                    + "Set SPRING_MAIL_HOST (see .env.example) to turn them on.");
            return 0;
        }
        int sent = 0;
        for (ApiKey key : keys.findExpiringBetween(today, today.plusDays(ReminderThreshold.horizonDays()))) {
            long daysRemaining = ChronoUnit.DAYS.between(today, key.getExpiresOn());
            ReminderThreshold threshold = ReminderThreshold.forDaysRemaining(daysRemaining).orElse(null);
            if (threshold == null || alreadySent(key, threshold)) {
                continue;
            }
            if (mailer.send(key, threshold, daysRemaining)) {
                reminders.save(new ExpiryReminder(key.getId(), key.getExpiresOn(), threshold,
                        key.getOwner().getEmail(), Instant.now()));
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Sent {} expiry reminder(s)", sent);
        }
        return sent;
    }

    private boolean alreadySent(ApiKey key, ReminderThreshold threshold) {
        return reminders.existsByApiKeyIdAndExpiresOnAndThresholdDays(key.getId(), key.getExpiresOn(),
                threshold.getDays());
    }
}
