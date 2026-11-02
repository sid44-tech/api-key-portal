package com.sidbhat.apikeyportal.rotation;

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
 * old key keeps its place in the list until it actually expires; the portal
 * never deletes it, at either end.
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

    public KeyRotationService(ApiKeyRepository keys, ServiceIntegrationRepository integrations,
            AutoRotationPreferenceRepository preferences, KeyProvisioningService provisioning,
            KeyRotationMailer mailer, RotationProperties properties, AuditLog audit) {
        this.keys = keys;
        this.integrations = integrations;
        this.preferences = preferences;
        this.provisioning = provisioning;
        this.mailer = mailer;
        this.properties = properties;
        this.audit = audit;
    }

    /** @return how many keys were replaced */
    public int rotateDueKeys() {
        return rotateDueKeys(LocalDate.now());
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
            boolean overlaps = integration.getProvider().rotationOverlaps();
            try {
                ApiKey rotatedKey = provisioning.rotate(key, integration);
                audit.allowed("key_rotated", key.getOwner().getEmail(),
                        key.getServiceName() + " / " + key.getName(),
                        overlaps
                                ? "Replacement minted automatically; the old key expires on " + key.getExpiresOn()
                                : "Rotated in place automatically; the previous value was revoked by the provider");
                mailer.send(key, rotatedKey, overlaps);
                rotated++;
            } catch (ProvisioningException e) {
                // next sweep tries again, and the expiry reminders keep running
                // underneath - a failed rotation never becomes a silent expiry
                log.error("Could not mint a replacement for {} through {}: {}", key, integration, e.getMessage());
                audit.refused("key_rotated", key.getOwner().getEmail(),
                        key.getServiceName() + " / " + key.getName(), e.getMessage());
            }
        }
        if (rotated > 0) {
            log.info("Replaced {} expiring key(s)", rotated);
        }
        return rotated;
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
