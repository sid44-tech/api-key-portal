package io.github.apikeyportal.alerts;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.apikeyportal.alerts.AlertCard.Fact;
import io.github.apikeyportal.alerts.AlertCard.Severity;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.notification.NotificationProperties;
import io.github.apikeyportal.notification.ReminderThreshold;
import io.github.apikeyportal.theme.ThemeService;

/**
 * Where the rest of the portal hands an alert and walks away.
 * <p>
 * Every method here is best effort and never throws: a webhook that is down,
 * deleted or slow must not stop the thing being announced - the reminder
 * sweep, the rotation sweep, a sign-in refusal. A failure is logged and kept on
 * the channel, where the integrations page shows it.
 * <p>
 * As with the emails, no key value ever goes near a card. Names, dates, owners
 * and a link back into the portal, where revealing is owner-only and recorded.
 */
@Service
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final AlertChannelRepository channels;
    private final WebhookClient client;
    private final NotificationProperties properties;
    private final ThemeService theme;

    public AlertNotifier(AlertChannelRepository channels, WebhookClient client, NotificationProperties properties,
            ThemeService theme) {
        this.channels = channels;
        this.client = client;
        this.properties = properties;
        this.theme = theme;
    }

    /** True if any group has asked for expiry alerts, so the sweep is worth running without SMTP. */
    public boolean wantsExpiries() {
        return channels.existsByEnabledTrueAndNotifyExpiriesTrue();
    }

    /**
     * A key approaching expiry, at one of the reminder thresholds.
     *
     * @return true if at least one channel took the card, so the sweep can count it as announced
     */
    public boolean expiring(ApiKey key, ReminderThreshold threshold, long daysRemaining) {
        String when = daysRemaining == 0 ? "today" : daysRemaining == 1 ? "tomorrow" : "in " + daysRemaining + " days";
        AlertCard card = new AlertCard(daysRemaining <= 1 ? Severity.ATTENTION : Severity.WARNING,
                "Key expires " + when + ": " + key.getServiceName() + " / " + key.getName(),
                "The " + threshold.getLabel() + " reminder. The owner has been emailed too, if mail is configured.",
                List.of(new Fact("Owner", key.getOwnerName()),
                        new Fact("Expires on", DATE.format(key.getExpiresOn())),
                        new Fact("Service", key.getServiceName())),
                "Open the key", keyUrl(key));
        boolean any = false;
        for (AlertChannel channel : channelsFor(key)) {
            if (channel.isNotifyExpiries()) {
                any |= post(channel, card);
            }
        }
        return any;
    }

    /** The sweep could not replace, or delete at the provider, this key. */
    public void rotationFailed(ApiKey key, String message) {
        AlertCard card = new AlertCard(Severity.ATTENTION,
                "Automatic replacement failed: " + key.getServiceName() + " / " + key.getName(),
                "It will be tried again on the next daily sweep. A refusal that keeps coming back usually "
                        + "means the integration's credential or permissions need looking at.",
                List.of(new Fact("Owner", key.getOwnerName()),
                        new Fact("Expires on", key.getExpiresOn() == null ? "Never" : DATE.format(key.getExpiresOn())),
                        new Fact("What went wrong", message)),
                "Open the key", keyUrl(key));
        for (AlertChannel channel : channelsFor(key)) {
            if (channel.isNotifyRotationFailures()) {
                post(channel, card);
            }
        }
    }

    /** Too many failed passwords against one account. Goes to every channel that asked for lockouts. */
    public void accountLockedOut(String email, Instant until) {
        lockout("Sign-in locked for an account", "Repeated wrong passwords for " + email
                + ". Nobody can sign in to that account until the lock lifts; if that is not its owner "
                + "trying, somebody is guessing.", new Fact("Account", email), until);
    }

    /** Too many failed passwords from one address. */
    public void addressLockedOut(String address, Instant until) {
        lockout("Sign-in locked for an address", "Repeated wrong passwords from " + address
                + ", possibly across several accounts. Everyone behind that address is refused until the lock lifts.",
                new Fact("Address", address), until);
    }

    private void lockout(String title, String text, Fact subject, Instant until) {
        List<AlertChannel> interested = channels.findByEnabledTrueAndNotifyLockoutsTrue();
        if (interested.isEmpty()) {
            return;
        }
        AlertCard card = new AlertCard(Severity.ATTENTION, title, text,
                List.of(subject, new Fact("Locked until", TIME.format(until.atZone(ZoneId.systemDefault())) + " ("
                        + ZoneId.systemDefault().getId() + ")")),
                "Open " + theme.portalName(), properties.getBaseUrl());
        for (AlertChannel channel : interested) {
            post(channel, card);
        }
    }

    /** Sends the card an admin asked for to prove the webhook works. Throws, because the admin is waiting. */
    public void sendTest(AlertChannel channel) {
        AlertCard card = new AlertCard(Severity.GOOD, theme.portalName() + " is connected to this channel",
                "Alerts for " + channel.getGroup().getName() + " will arrive here" + describeToggles(channel) + ".",
                List.of(new Fact("Channel", channel.getName())), "Open " + theme.portalName(),
                properties.getBaseUrl());
        client.post(channel.getProvider(), channel.getWebhookUrl(), card);
    }

    private static String describeToggles(AlertChannel channel) {
        StringBuilder text = new StringBuilder(": ");
        boolean any = false;
        if (channel.isNotifyExpiries()) {
            text.append("keys about to expire");
            any = true;
        }
        if (channel.isNotifyRotationFailures()) {
            text.append(any ? ", " : "").append("failed automatic replacements");
            any = true;
        }
        if (channel.isNotifyLockouts()) {
            text.append(any ? ", " : "").append("sign-in lockouts");
            any = true;
        }
        return any ? text.toString() : " once something is switched on";
    }

    private List<AlertChannel> channelsFor(ApiKey key) {
        if (key.getGroup() == null) {
            return List.of();
        }
        return channels.findByGroup_IdOrderByProvider(key.getGroup().getId()).stream()
                .filter(AlertChannel::isEnabled).toList();
    }

    /** Posts and records how it went on the channel. Never throws. */
    private boolean post(AlertChannel channel, AlertCard card) {
        try {
            client.post(channel.getProvider(), channel.getWebhookUrl(), card);
            channel.markSent(Instant.now());
            channels.save(channel);
            log.info("Posted to {}: {}", channel, card.title());
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not post to {}: {}", channel, e.getMessage());
            try {
                channel.markFailed(e.getMessage());
                channels.save(channel);
            } catch (RuntimeException ignored) {
                // the database is the problem; the warning above is all there is to do
            }
            return false;
        }
    }

    private String keyUrl(ApiKey key) {
        return UriComponentsBuilder.fromUriString(properties.getBaseUrl()).path("/keys/{id}")
                .buildAndExpand(key.getId()).toUriString();
    }
}
