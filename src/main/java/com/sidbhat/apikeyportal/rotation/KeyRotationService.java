package com.sidbhat.apikeyportal.rotation;

import java.time.Instant;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.integration.KeyProvisioningService;
import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationRepository;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.logging.AuditLog;
import com.sidbhat.apikeyportal.alerts.AlertNotifier;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * Replaces expiring keys before they expire, for owners who have asked for it.
 * <p>
 * The decision belongs to the owner twice over: a per-provider setting on their
 * profile ("the portal may mint replacements for my Azure AD keys"), and a
 * per-key override pinning one key on or off regardless. No setting, no
 * rotation - minting credentials on somebody's behalf is strictly opt-in.
 * <p>
 * The sweep runs daily and mints the replacement {@code leadDays} before expiry,
 * so both secrets work during the hand-over and nothing stops at midnight. The
 * old key keeps its place in the list until it actually expires, and the portal
 * never deletes the row.
 * <p>
 * At the provider, it depends who retires credentials. Azure expires a secret on
 * the date it was given, and GitLab revokes as it rotates. Google and AWS never
 * retire a key at all, so for those the second half of the sweep does it: once a
 * replaced key's date has passed, the portal deletes the old credential at the
 * provider. That is what makes "expired" true for them rather than a note.
 * <p>
 * Failure is undramatic by design: a provider refusal is logged and retried on
 * the next sweep, the ordinary expiry reminders keep running as the safety net,
 * and a unique index on the replacement link guarantees that no key is ever
 * replaced twice however often the sweep looks at it.
 */
@Service
public class KeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationService.class);

    /**
     * How close to expiry a key gets before a provider that cannot overlap is
     * rotated. One day: late enough that almost nothing is broken early, with a
     * sweep still to spare if the provider is having a bad morning.
     */
    static final int NO_OVERLAP_LEAD_DAYS = 1;

    private final ApiKeyRepository keys;
    private final ServiceIntegrationRepository integrations;
    private final AutoRotationPreferenceRepository preferences;
    private final KeyProvisioningService provisioning;
    private final KeyRotationMailer mailer;
    private final RotationProperties properties;
    private final AuditLog audit;
    private final AlertNotifier alerts;

    public KeyRotationService(ApiKeyRepository keys, ServiceIntegrationRepository integrations,
            AutoRotationPreferenceRepository preferences, KeyProvisioningService provisioning,
            KeyRotationMailer mailer, RotationProperties properties, AuditLog audit, AlertNotifier alerts) {
        this.keys = keys;
        this.integrations = integrations;
        this.preferences = preferences;
        this.provisioning = provisioning;
        this.mailer = mailer;
        this.properties = properties;
        this.audit = audit;
        this.alerts = alerts;
    }

    /** @return how many keys were replaced */
    public int rotateDueKeys() {
        return rotateDueKeys(LocalDate.now());
    }

    /** @return how many expired, already-replaced credentials were deleted at their provider */
    public int revokeExpiredKeys() {
        return revokeExpiredKeys(LocalDate.now());
    }

    /**
     * The date is a parameter so the sweep can be tested on any day it likes.
     * <p>
     * Deliberately not transactional: each rotation talks to a provider, and a
     * database transaction must not be held open across that. Each stored
     * replacement is atomic on its own, and one failure doesn't take the rest of
     * the morning's rotations with it.
     */
    public int rotateDueKeys(LocalDate today) {
        int rotated = 0;
        for (ApiKey key : keys.findRotationCandidates(today, today.plusDays(properties.getLeadDays()))) {
            ServiceIntegration integration = integrations.findById(key.getIntegrationId()).orElse(null);
            // a switched-off integration mints nothing, replacements included
            if (integration == null || !integration.isEnabled()
                    || !integration.getProvider().supportsRotation()
                    || !wantsRotation(key, integration.getProvider())
                    || !isDue(key, integration.getProvider(), today)) {
                continue;
            }
            ServiceProvider provider = integration.getProvider();
            boolean willDelete = deletesReplaced(integration);
            try {
                ApiKey rotatedKey = provisioning.rotate(key, integration);
                forgetFailure(key);
                audit.allowed("key_rotated", key.getOwner().getEmail(),
                        key.getServiceName() + " / " + key.getName(), rotationAuditDetail(key, provider, willDelete));
                mailer.send(key, rotatedKey, provider, willDelete);
                rotated++;
            } catch (ProvisioningException e) {
                // next sweep tries again, and the expiry reminders keep running
                // underneath - a failed rotation never becomes a silent expiry
                log.error("Could not mint a replacement for {} through {}: {}", key, integration, e.getMessage());
                audit.refused("key_rotated", key.getOwner().getEmail(),
                        key.getServiceName() + " / " + key.getName(), e.getMessage());
                rememberFailure(key, e.getMessage());
            } catch (RuntimeException e) {
                // anything else - a database hiccup saving the replacement, say -
                // is this key's problem, not the morning's: the next key still
                // gets its turn, and this one is retried tomorrow like a refusal.
                // Logged with the stack, unlike a refusal: nobody expected this one.
                log.error("Unexpected error replacing {} through {}", key, integration, e);
                audit.refused("key_rotated", key.getOwner().getEmail(),
                        key.getServiceName() + " / " + key.getName(), "Unexpected error: " + e);
                rememberFailure(key, "Unexpected error: " + e);
            }
        }
        if (rotated > 0) {
            log.info("Replaced {} expiring key(s)", rotated);
        }
        return rotated;
    }

    /**
     * Puts the failure on the key, where the dashboard and the key's page can
     * show it. The audit log already has it; this is the copy a page can query.
     * Best effort: if the database is the thing that is broken, the sweep still
     * moves on to the next key.
     */
    private void rememberFailure(ApiKey key, String message) {
        try {
            keys.markRotationFailed(key.getId(), Instant.now(), abbreviate(message));
        } catch (RuntimeException e) {
            log.warn("Could not record the failed rotation of {}: {}", key, e.getMessage());
        }
        // the group's chat channels, if it has any that want to hear; never throws
        alerts.rotationFailed(key, abbreviate(message));
    }

    private void forgetFailure(ApiKey key) {
        try {
            keys.clearRotationFailure(key.getId());
        } catch (RuntimeException e) {
            log.warn("Could not clear the recorded failure on {}: {}", key, e.getMessage());
        }
    }

    /** The column holds 500 characters; a provider's error page can be longer. */
    private static String abbreviate(String message) {
        String text = message == null ? "Failed without a message" : message;
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    /** Whether this integration deletes a replaced key at the provider once it has expired. */
    static boolean deletesReplaced(ServiceIntegration integration) {
        return integration.getProvider().canDeleteReplaced() && integration.isDeleteReplacedKeys();
    }

    private static String rotationAuditDetail(ApiKey key, ServiceProvider provider, boolean willDelete) {
        if (!provider.rotationOverlaps()) {
            return "Rotated in place automatically; the previous value was revoked by the provider";
        }
        if (willDelete) {
            return "Replacement minted automatically; the old key will be deleted at " + provider.getLabel()
                    + " after it expires on " + key.getExpiresOn();
        }
        if (provider.revokedByPortal()) {
            return "Replacement minted automatically; the old key expires on " + key.getExpiresOn()
                    + " but deletion at " + provider.getLabel() + " is switched off, so it keeps working there";
        }
        return "Replacement minted automatically; the old key expires on " + key.getExpiresOn();
    }

    /**
     * The second half of the sweep: for providers that never retire a credential
     * themselves, delete each expired key at the provider once its replacement
     * exists. Only replaced keys - a key nobody asked to replace keeps its date as
     * the reminder it always was, because deleting somebody's only working
     * credential on a date is an outage, not a rotation.
     * <p>
     * Not transactional, for the same reason as {@link #rotateDueKeys(LocalDate)}:
     * a provider call per key, each recorded on its own, no failure taking the
     * rest with it. A refusal is audited and tried again tomorrow; until it
     * succeeds the key's page keeps saying the deletion is still to come.
     *
     * @return how many credentials were deleted at their provider
     */
    public int revokeExpiredKeys(LocalDate today) {
        int revoked = 0;
        for (ApiKey key : keys.findRevocationCandidates(today)) {
            ServiceIntegration integration = integrations.findById(key.getIntegrationId()).orElse(null);
            // a switched-off integration is paused, deletions included; one that
            // has deletion switched off leaves the old credential where it is
            if (integration == null || !integration.isEnabled() || !deletesReplaced(integration)) {
                continue;
            }
            try {
                provisioning.revoke(key, integration);
                forgetFailure(key);
                revoked++;
            } catch (ProvisioningException e) {
                log.error("Could not delete the expired {} at {}: {}", key, integration, e.getMessage());
                audit.refused("key_revoked", key.getOwner().getEmail(),
                        key.getServiceName() + " / " + key.getName(), e.getMessage());
                rememberFailure(key, "Could not delete at the provider: " + e.getMessage());
            } catch (RuntimeException e) {
                // same rule as the minting loop: one key's surprise must not
                // leave the rest of the expired keys alive at their providers
                log.error("Unexpected error deleting the expired {} at {}", key, integration, e);
                audit.refused("key_revoked", key.getOwner().getEmail(),
                        key.getServiceName() + " / " + key.getName(), "Unexpected error: " + e);
                rememberFailure(key, "Unexpected error deleting at the provider: " + e);
            }
        }
        if (revoked > 0) {
            log.info("Deleted {} expired key(s) at their provider", revoked);
        }
        return revoked;
    }

    /**
     * Whether this key is close enough to expiry to rotate <em>now</em>.
     * <p>
     * The configured lead exists to give a hand-over room to breathe: both
     * credentials are live, so rotating a week early costs nothing and lets the
     * owner switch over calmly. Where rotation revokes the old credential
     * instantly, that reasoning inverts - rotating a week early breaks whatever
     * is using the key a week earlier than it had to. So those providers are
     * left until the last possible sweep, when the key is about to stop working
     * anyway and rotating can only improve matters.
     */
    private boolean isDue(ApiKey key, ServiceProvider provider, LocalDate today) {
        if (provider.rotationOverlaps()) {
            return true;
        }
        return !key.getExpiresOn().isAfter(today.plusDays(NO_OVERLAP_LEAD_DAYS));
    }

    /** The owner's answer for this key: its own override if pinned, their provider setting otherwise. */
    private boolean wantsRotation(ApiKey key, ServiceProvider provider) {
        if (key.getAutoRotate() != null) {
            return key.getAutoRotate();
        }
        return isPreferenceEnabled(key.getOwner(), provider);
    }

    /** Whether {@code user} has switched automatic replacement on for {@code provider}. */
    public boolean isPreferenceEnabled(UserAccount user, ServiceProvider provider) {
        return preferences.findByUserIdAndProvider(user.getId(), provider)
                .map(AutoRotationPreference::isEnabled)
                .orElse(false);
    }

    /** Flips {@code user}'s own per-provider setting. Theirs alone - there is no admin override. */
    @Transactional
    public void setPreference(UserAccount user, ServiceProvider provider, boolean enabled) {
        AutoRotationPreference preference = preferences.findByUserIdAndProvider(user.getId(), provider)
                .orElseGet(() -> new AutoRotationPreference(user.getId(), provider, enabled));
        preference.setEnabled(enabled);
        preferences.save(preference);
        audit.allowed("auto_rotation_changed", user.getEmail(), provider.getLabel(),
                enabled ? "Switched on" : "Switched off");
    }

    /**
     * True if this key could be rotated at all: minted through an integration that
     * still exists and whose provider the portal can mint fresh secrets in. What
     * the key's page offers hangs on this; whether rotation then happens is the
     * owner's settings.
     */
    public boolean supportsRotation(ApiKey key) {
        return providerOf(key) != null;
    }

    /**
     * Whether rotating this key leaves the old value working. False means a
     * hard swap, which the key's page has to say plainly.
     */
    public boolean rotationOverlaps(ApiKey key) {
        ServiceProvider provider = providerOf(key);
        return provider != null && provider.rotationOverlaps();
    }

    /**
     * Whether the portal will delete this key at the provider once it has expired
     * and been replaced: the provider can, and the integration's switch is on.
     * The key's page says so plainly, since it is the one place automatic
     * replacement ends in a deletion.
     */
    public boolean deletesReplaced(ApiKey key) {
        return key.isRotatable() && integrations.findById(key.getIntegrationId())
                .filter(ServiceIntegration::isEnabled)
                .map(KeyRotationService::deletesReplaced)
                .orElse(false);
    }

    /** Whether the provider never retires this key itself, so only the portal's deletion makes its expiry real. */
    public boolean providerNeverExpires(ApiKey key) {
        ServiceProvider provider = providerOf(key);
        return provider != null && provider.revokedByPortal();
    }

    /** The owner-facing state of the provider setting behind {@code key}, for its page. */
    public boolean isEnabledForProviderOf(ApiKey key, UserAccount viewer) {
        ServiceProvider provider = providerOf(key);
        return provider != null && isPreferenceEnabled(viewer, provider);
    }

    private ServiceProvider providerOf(ApiKey key) {
        if (!key.isRotatable()) {
            return null;
        }
        return integrations.findById(key.getIntegrationId())
                // off means no rotation, so the key's page should not offer it
                .filter(ServiceIntegration::isEnabled)
                .map(ServiceIntegration::getProvider)
                .filter(ServiceProvider::supportsRotation)
                .orElse(null);
    }
}
