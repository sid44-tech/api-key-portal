package io.github.apikeyportal.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.apikeyportal.activity.ActivityAction;
import io.github.apikeyportal.activity.ActivityService;
import io.github.apikeyportal.integration.ServiceIntegrationService;
import io.github.apikeyportal.user.UserAccount;

/**
 * Connecting, changing and removing a group's chat channels. A group admin's
 * job, like the provider integrations: the webhook can post into the channel
 * as the portal, so it is not a member's to manage.
 */
@Service
@Transactional(readOnly = true)
public class AlertChannelService {

    private static final Logger log = LoggerFactory.getLogger(AlertChannelService.class);

    private final AlertChannelRepository repository;
    private final AlertNotifier notifier;
    private final ActivityService activity;

    public AlertChannelService(AlertChannelRepository repository, AlertNotifier notifier, ActivityService activity) {
        this.repository = repository;
        this.notifier = notifier;
        this.activity = activity;
    }

    /** Every channel connected for {@code viewer}'s group. */
    public List<AlertChannel> findForGroupOf(UserAccount viewer) {
        return viewer.getGroupId() == null ? List.of() : repository.findByGroup_IdOrderByProvider(viewer.getGroupId());
    }

    /** The group's channel in one product, if connected. */
    public Optional<AlertChannel> find(UserAccount viewer, ChannelProvider provider) {
        return viewer.getGroupId() == null ? Optional.empty()
                : repository.findByGroup_IdAndProvider(viewer.getGroupId(), provider);
    }

    /**
     * Connects or updates the group's channel in {@code provider}. A test card is
     * posted first, so a saved channel is known to have worked at least once;
     * nothing is stored if the service refuses.
     *
     * @param webhookUrl the new URL, or blank to keep the one already stored
     * @throws AlertException if the URL is unusable or the service rejected the test
     */
    @Transactional
    public AlertChannel save(ChannelProvider provider, String name, String webhookUrl, boolean enabled,
            boolean notifyExpiries, boolean notifyRotationFailures, boolean notifyLockouts, UserAccount admin) {
        requireGroupAdmin(admin);
        Optional<AlertChannel> existing = find(admin, provider);
        String url = webhookUrl == null || webhookUrl.isBlank()
                ? existing.map(AlertChannel::getWebhookUrl).orElseThrow(
                        () -> new AlertException("Paste the webhook URL from " + provider.getLabel() + "."))
                : WebhookClient.validate(webhookUrl);

        AlertChannel channel = existing.orElseGet(() -> new AlertChannel(admin.getGroup(), provider, name.trim(), url));
        channel.setName(name.trim());
        channel.setWebhookUrl(url);
        channel.setEnabled(enabled);
        channel.setNotifyExpiries(notifyExpiries);
        channel.setNotifyRotationFailures(notifyRotationFailures);
        channel.setNotifyLockouts(notifyLockouts);

        // proves the webhook before anything is stored; throws if the service says no
        notifier.sendTest(channel);
        channel.markSent(Instant.now());

        AlertChannel saved = repository.save(channel);
        activity.record(ActivityAction.INTEGRATION_CONNECTED, admin, saved.getId(), saved.getName(),
                provider.getLabel() + (existing.isPresent() ? " (settings changed)" : ""));
        log.info("{} connected {}", admin.getEmail(), saved);
        return saved;
    }

    /** Posts the test card again, on request. */
    public void sendTest(ChannelProvider provider, UserAccount admin) {
        requireGroupAdmin(admin);
        AlertChannel channel = find(admin, provider).orElseThrow(
                () -> new AlertException("No " + provider.getLabel() + " channel is connected for your group."));
        notifier.sendTest(channel);
    }

    @Transactional
    public void remove(ChannelProvider provider, UserAccount admin) {
        requireGroupAdmin(admin);
        find(admin, provider).ifPresent(channel -> {
            repository.delete(channel);
            activity.record(ActivityAction.INTEGRATION_REMOVED, admin, channel.getId(), channel.getName(),
                    provider.getLabel());
            log.info("{} removed {}", admin.getEmail(), channel);
        });
    }

    private static void requireGroupAdmin(UserAccount admin) {
        if (!ServiceIntegrationService.canManage(admin)) {
            throw new AccessDeniedException("Only a group admin can manage alert channels");
        }
    }
}
