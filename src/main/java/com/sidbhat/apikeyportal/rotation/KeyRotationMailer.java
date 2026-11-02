package com.sidbhat.apikeyportal.rotation;

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
import com.sidbhat.apikeyportal.notification.NotificationProperties;

/**
 * Tells a key's owner that their key is about to expire and a replacement has
 * already been minted for it.
 * <p>
 * Neither value goes anywhere near the email - not the expiring one and not the
 * new one. The email names the keys and links to the portal, where revealing the
 * new value is owner-only, on demand, and recorded like any other reveal.
 */
@Component
public class KeyRotationMailer {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationMailer.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");

    private final ObjectProvider<JavaMailSender> mailSender;
    private final NotificationProperties properties;

    public KeyRotationMailer(ObjectProvider<JavaMailSender> mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * @return true if the email went out. A failure is logged and not retried:
     *         the replacement already exists either way, the portal shows it
     *         against the old key, and the ordinary expiry reminders for other
     *         keys are unaffected.
     */
    public boolean send(ApiKey expiring, ApiKey replacement, boolean overlaps) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.info("No mail server configured; {} was replaced without an email to {}",
                    expiring, expiring.getOwner().getEmail());
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(expiring.getOwner().getEmail());
        message.setSubject(overlaps ? subject(expiring) : rotatedSubject(expiring));
        message.setText(overlaps ? body(expiring, replacement) : rotatedBody(replacement));
        try {
            sender.send(message);
            log.info("Sent the replacement-ready email for {} to {}", expiring, expiring.getOwner().getEmail());
            return true;
        } catch (MailException e) {
            log.error("Could not send the replacement-ready email for {} to {}", expiring,
                    expiring.getOwner().getEmail(), e);
            return false;
        }
    }

    /**
     * The no-overlap case is not a heads-up, it is a notification that something
     * has already changed - and probably broken. The subject has to say so.
     */
    static String rotatedSubject(ApiKey rotated) {
        return "Action needed: your " + rotated.getServiceName() + " key \"" + rotated.getName()
                + "\" has been rotated";
    }

    /**
     * No hand-over to explain: the previous value stopped working the moment the
     * provider issued this one, so the only useful message is "update whatever
     * uses this, now, and here is where to get it".
     */
    String rotatedBody(ApiKey rotated) {
        StringBuilder text = new StringBuilder();
        text.append("Hi ").append(rotated.getOwner().getDisplayName()).append(",\n\n");
        text.append("Your key was about to expire, so a new value has been generated for it.\n\n");
        text.append("IMPORTANT: ").append(rotated.getServiceName())
                .append(" revokes the previous value as soon as it issues a new one, so anything still\n");
        text.append("using the old value has stopped working. Update it with the new value now.\n\n");
        text.append("Key:        ").append(rotated.getName()).append('\n');
        text.append("Service:    ").append(rotated.getServiceName()).append('\n');
        text.append("Expires on: ").append(DATE.format(rotated.getExpiresOn())).append('\n');
        text.append("New value:  ").append(keyUrl(rotated)).append('\n');
        text.append('\n');
        text.append("The key itself is unchanged in the portal - same name, same notes - it simply holds\n");
        text.append("the new value now.\n\n");
        text.append("Sent by the API Key Portal because automatic replacement is switched on for your ")
                .append(rotated.getServiceName()).append(" keys. You can turn it off on your profile, ")
                .append("or for this key alone on its page.\n");
        return text.toString();
    }

    static String subject(ApiKey expiring) {
        return "Your " + expiring.getServiceName() + " key \"" + expiring.getName()
                + "\" is about to expire - a replacement is ready";
    }

    String body(ApiKey expiring, ApiKey replacement) {
        StringBuilder text = new StringBuilder();
        text.append("Hi ").append(expiring.getOwner().getDisplayName()).append(",\n\n");
        text.append("Your key is about to expire and a new one has been generated for it.\n\n");
        text.append("Expiring key:\n");
        text.append("  Service:    ").append(expiring.getServiceName()).append('\n');
        text.append("  Name:       ").append(expiring.getName()).append('\n');
        text.append("  Expires on: ").append(DATE.format(expiring.getExpiresOn())).append('\n');
        text.append('\n');
        text.append("Its replacement:\n");
        text.append("  Expires on: ").append(DATE.format(replacement.getExpiresOn())).append('\n');
        text.append("  Value:      ").append(keyUrl(replacement)).append('\n');
        text.append('\n');
        text.append("Both keys work until the old one expires, so you can switch over whenever suits.\n");
        text.append("The old key stays in your list until it expires, then moves to the expired list.\n\n");
        text.append("Sent by the API Key Portal because automatic replacement is switched on for your ")
                .append(expiring.getServiceName()).append(" keys. You can turn it off on your profile, ")
                .append("or for this key alone on its page.\n");
        return text.toString();
    }

    private String keyUrl(ApiKey key) {
        return UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .path("/keys/{id}")
                .buildAndExpand(key.getId())
                .toUriString();
    }
}
