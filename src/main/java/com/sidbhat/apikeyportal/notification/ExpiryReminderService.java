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
import com.sidbhat.apikeyportal.alerts.AlertNotifier;

/**
 * The daily sweep: find keys inside a reminder threshold that haven't been
 * reminded at that threshold yet, email their owners, and record it.
 * <p>
 * Recording happens only after a successful send, so a mail outage means a
 * retry tomorrow rather than a reminder nobody received marked as sent. A
 * reminder counts as sent if it reached the owner by email <em>or</em> the
 * group's chat channel: either is somewhere a person will see it, and a
 * portal with a channel and no mail server still announces its expiries.
 */
@Service
public class ExpiryReminderService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReminderService.class);

    private final ApiKeyRepository keys;
    private final ExpiryReminderRepository reminders;
    private final ExpiryReminderMailer mailer;
    private final AlertNotifier alerts;

    public ExpiryReminderService(ApiKeyRepository keys, ExpiryReminderRepository reminders,
            ExpiryReminderMailer mailer, AlertNotifier alerts) {
        this.keys = keys;
        this.reminders = reminders;
        this.mailer = mailer;
        this.alerts = alerts;
    }

    /** @return how many reminders went out */
    @Transactional
    public int sendDueReminders() {
        return sendDueReminders(LocalDate.now());
    }

    /** The date is a parameter so the sweep can be tested on any day it likes. */
    @Transactional
    public int sendDueReminders(LocalDate today) {
        boolean mailable = mailer.isAvailable();
        if (!mailable && !alerts.wantsExpiries()) {
            log.warn("No mail server configured and no chat channel wants expiries; reminders cannot be sent. "
                    + "Set SPRING_MAIL_HOST (see .env.example) or connect a Teams or Slack channel to turn them on.");
            return 0;
        }
        int sent = 0;
        for (ApiKey key : keys.findExpiringBetween(today, today.plusDays(ReminderThreshold.horizonDays()))) {
            if (keys.existsByRotatedFromKeyId(key.getId())) {
                // a replacement has already been minted and announced; warning
                // about the old key expiring is the point of the whole exercise
                continue;
            }
            long daysRemaining = ChronoUnit.DAYS.between(today, key.getExpiresOn());
            ReminderThreshold threshold = ReminderThreshold.forDaysRemaining(daysRemaining).orElse(null);
            if (threshold == null || alreadySent(key, threshold)) {
                continue;
            }
            boolean emailed = mailable && mailer.send(key, threshold, daysRemaining);
            boolean posted = alerts.expiring(key, threshold, daysRemaining);
            if (emailed || posted) {
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
