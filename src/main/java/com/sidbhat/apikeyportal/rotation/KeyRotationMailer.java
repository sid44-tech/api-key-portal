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

import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.notification.NotificationProperties;
import com.sidbhat.apikeyportal.theme.ThemeService;

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
    private final ThemeService theme;

    public KeyRotationMailer(ObjectProvider<JavaMailSender> mailSender, NotificationProperties properties,
            ThemeService theme) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.theme = theme;
    }

    /**
     * @param provider   decides what the email can promise: an overlap or an
     *                   instant swap, and whether the old key's end is the
     *                   provider's doing or the portal's
     * @param willDelete whether the portal deletes the old key at the provider
     *                   once it has expired - the integration's switch
     * @return true if the email went out. A failure is logged and not retried:
     *         the replacement already exists either way, the portal shows it
     *         against the old key, and the ordinary expiry reminders for other
     *         keys are unaffected.
     */
    public boolean send(ApiKey expiring, ApiKey replacement, ServiceProvider provider, boolean willDelete) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.info("No mail server configured; {} was replaced without an email to {}",
                    expiring, expiring.getOwner().getEmail());
            return false;
        }
        boolean overlaps = provider.rotationOverlaps();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFromAddress());
        message.setTo(expiring.getOwner().getEmail());
        message.setSubject(overlaps ? subject(expiring) : rotatedSubject(expiring));
        message.setText(overlaps ? body(expiring, replacement, provider, willDelete) : rotatedBody(replacement));
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
        text.append("Sent by ").append(theme.portalName())
                .append(" because automatic replacement is switched on for your ")
                .append(rotated.getServiceName()).append(" keys. You can turn it off on your profile, ")
                .append("or for this key alone on its page.\n");
        return text.toString();
    }

    static String subject(ApiKey expiring) {
        return "Your " + expiring.getServiceName() + " key \"" + expiring.getName()
                + "\" is about to expire - a replacement is ready";
    }

    /**
     * The hand-over email. Where the provider never retires a key itself, the
     * owner has to be told the portal will: a deletion on a date is the one part
     * of automatic replacement that can break something, and it must not come as
     * a surprise.
     */
    String body(ApiKey expiring, ApiKey replacement, ServiceProvider provider, boolean willDelete) {
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
        if (willDelete && provider.revokedByPortal()) {
            text.append("Both keys work until the old one expires, so you can switch over whenever suits -\n");
            text.append("but do switch over. ").append(provider.getLabel())
                    .append(" never retires a key by itself, so on ").append(DATE.format(expiring.getExpiresOn()))
                    .append(" the portal will DELETE the old key there, and anything still using it will stop\n");
            text.append("working. The old key stays in your list until then, then moves to the expired list.\n\n");
        } else if (provider.revokedByPortal()) {
            text.append("Both keys work until the old one expires, so you can switch over whenever suits.\n");
            text.append("NOTE: ").append(provider.getLabel())
                    .append(" never retires a key by itself, and automatic deletion is switched off for this\n");
            text.append("integration, so the old key keeps working there after its date until somebody deletes\n");
            text.append("it in the ").append(provider.getLabel()).append(" console.\n\n");
        } else if (willDelete) {
            text.append("Both keys work until the old one expires, so you can switch over whenever suits.\n");
            text.append("Once it has expired, the portal removes the old secret from the app registration to\n");
            text.append("keep it tidy; the key stays in your list, in the expired section.\n\n");
        } else {
            text.append("Both keys work until the old one expires, so you can switch over whenever suits.\n");
            text.append("The old key stays in your list until it expires, then moves to the expired list.\n\n");
        }
        text.append("Sent by ").append(theme.portalName())
                .append(" because automatic replacement is switched on for your ")
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
