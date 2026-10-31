package com.sidbhat.apikeyportal.notification;

import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.sidbhat.apikeyportal.key.ApiKey;

/**
 * Writes and sends the "your key is about to expire" email.
 * <p>
 * Everything about the key goes in except the one thing that must not: the
 * value. Email is the least private channel the portal has.
 */
@Component
public class ExpiryReminderMailer {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReminderMailer.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");

    private final ObjectProvider<JavaMailSender> mailSender;
    private final NotificationProperties properties;

    public ExpiryReminderMailer(ObjectProvider<JavaMailSender> mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** True if a mail server is configured at all; the sweep is pointless without one. */
    public boolean isAvailable() {
        return mailSender.getIfAvailable() != null;
    }

    /**
     * @return true if the email went out; false if the server rejected it, in which
     *         case the sweep leaves no record and tries again tomorrow
     */
    public boolean send(ApiKey key, ReminderThreshold threshold, long daysRemaining) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(key.getOwner().getEmail());
        message.setSubject(subject(key, daysRemaining));
        message.setText(body(key, threshold, daysRemaining));
        try {
            sender.send(message);
            log.info("Sent {} expiry reminder for {} to {}", threshold, key, key.getOwner().getEmail());
            return true;
        } catch (MailException e) {
            log.error("Could not send the expiry reminder for {} to {}", key, key.getOwner().getEmail(), e);
            return false;
        }
    }

    /** Also where the link in the email lands, so the toggle is right there. */
    public String keyUrl(ApiKey key) {
        return UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .path("/keys/{id}")
                .buildAndExpand(key.getId())
                .toUriString();
    }

    static String subject(ApiKey key, long daysRemaining) {
        String when = daysRemaining == 0 ? "today" : daysRemaining == 1 ? "tomorrow" : "in " + daysRemaining + " days";
        return "Your " + key.getServiceName() + " key \"" + key.getName() + "\" expires " + when;
    }

    String body(ApiKey key, ReminderThreshold threshold, long daysRemaining) {
        StringBuilder text = new StringBuilder();
        text.append("Hi ").append(key.getOwner().getDisplayName()).append(",\n\n");
        text.append("A key you hold in the API Key Portal expires ").append(describe(daysRemaining)).append(".\n\n");
        text.append("Service:     ").append(key.getServiceName()).append('\n');
        text.append("Name:        ").append(key.getName()).append('\n');
        text.append("Expires on:  ").append(DATE.format(key.getExpiresOn())).append('\n');
        text.append("Created on:  ").append(DATE.format(key.getCreatedOn())).append('\n');
        if (key.getGroup() != null) {
            text.append("Group:       ").append(key.getGroup().getName()).append('\n');
        }
        if (key.getNotes() != null && !key.getNotes().isBlank()) {
            text.append("Notes:\n").append(indent(key.getNotes())).append('\n');
        }
        text.append("\nIf you've already rotated it, update the expiry date in the portal and these reminders")
                .append(" will start again for the new date:\n").append(keyUrl(key)).append("\n\n");
        text.append("Don't want to hear about this one? Open the key above and switch on")
                .append(" \"Mute expiration notifications\". Other keys are unaffected.\n\n");
        text.append("This is the ").append(threshold.getLabel()).append(" reminder.");
        if (threshold != ReminderThreshold.ONE_DAY) {
            text.append(" You'll get another closer to the date unless you mute it.");
        }
        text.append('\n');
        return text.toString();
    }

    private static String describe(long daysRemaining) {
        if (daysRemaining == 0) {
            return "today";
        }
        if (daysRemaining == 1) {
            return "tomorrow";
        }
        return "in " + daysRemaining + " days";
    }

    private static String indent(String notes) {
        return "    " + notes.strip().replace("\n", "\n    ");
    }
}
