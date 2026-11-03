package com.sidbhat.apikeyportal.key;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.sidbhat.apikeyportal.crypto.EncryptedStringConverter;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserGroup;

/**
 * A single API / access key for a third-party service.
 * <p>
 * Everyone in the owner's group can see a key and its metadata, but
 * {@link #getKeyValue()} is only ever handed back to the owner.
 */
@Entity
@Table(name = "api_key")
@EntityListeners(AuditingEntityListener.class)
public class ApiKey {

    private static final String MASK = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "service_name", nullable = false, length = 100)
    private String serviceName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "key_value", nullable = false, length = 2048)
    private String keyValue;

    /** Date the key was issued by the service. */
    @Column(name = "created_on", nullable = false)
    private LocalDate createdOn;

    /** Date the key stops working. {@code null} means the key does not expire. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /** Free-form notes, e.g. scopes or where the key is used. Not encrypted. */
    @Column(length = 1000)
    private String notes;

    /**
     * Whether the owner has asked not to be emailed as this key approaches its
     * expiry. Off by default: reminders are the point, silence is opt-in per key.
     */
    @Column(name = "expiry_notifications_muted", nullable = false)
    private boolean expiryNotificationsMuted;

    /**
     * Who added the key. Only they can reveal, edit or delete it.
     * Null only for keys that predate accounts; the bootstrap admin claims those.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "owner_id")
    private UserAccount owner;

    /**
     * Who can see the key listed. Held alongside the owner rather than read through
     * them, so moving someone to another group doesn't silently move their keys too.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id")
    private UserGroup group;

    @CreatedDate
    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set by {@link ApiKeyRepository#markRevealed} only, so it doesn't count as a modification. */
    @Column(name = "last_revealed_at", insertable = false, updatable = false)
    private Instant lastRevealedAt;

    /**
     * The integration this key was minted through, where there was one. A plain id
     * rather than a relation: removing the integration must not take its keys with
     * it, it just means no more replacements can be minted for them.
     */
    @Column(name = "integration_id")
    private Long integrationId;

    /**
     * The provider-side resource the key hangs off - for Azure AD, the app
     * registration's object id. A replacement secret is added to this same
     * client, so rotation changes the secret and never the consumer's identity.
     */
    @Column(name = "provider_resource_id", length = 255)
    private String providerResourceId;

    /**
     * The provider's id for this specific credential, where {@link #providerResourceId}
     * is what it hangs off: for Azure, the password credential's keyId on the app
     * registration. What a deletion at the provider deletes by; null for keys
     * minted before it was recorded, and for providers whose stored value
     * already identifies the credential (Google's key id, AWS's access key id).
     */
    @Column(name = "provider_credential_id", length = 255)
    private String providerCredentialId;

    /**
     * The owner's per-key say on automatic replacement: null defers to their
     * setting for the provider, true or false pins this one key regardless.
     */
    @Column(name = "auto_rotate")
    private Boolean autoRotate;

    /** Set on a replacement: the key this one was minted to take over from. */
    @Column(name = "rotated_from_key_id")
    private Long rotatedFromKeyId;

    /**
     * When the last automatic replacement or deletion of this key failed, and
     * why. Written by {@link ApiKeyRepository#markRotationFailed} only and
     * cleared by the next success, so - like {@link #lastRevealedAt} - it never
     * counts as the key being edited.
     */
    @Column(name = "rotation_failed_at", insertable = false, updatable = false)
    private Instant rotationFailedAt;

    @Column(name = "rotation_failure", insertable = false, updatable = false)
    private String rotationFailure;

    /**
     * When the portal deleted this credential at its provider, for providers whose
     * keys never expire on their own (Google, AWS). Null until then - and always,
     * for providers that retire their own credentials.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {
        // for JPA
    }

    public ApiKey(String name, String serviceName, String keyValue, LocalDate createdOn, LocalDate expiresOn) {
        this.name = name;
        this.serviceName = serviceName;
        this.keyValue = keyValue;
        this.createdOn = createdOn;
        this.expiresOn = expiresOn;
    }

    public Long getId() {
        return id;
    }

    /**
     * True if the portal still has everything it needs to mint a replacement:
     * which integration to use and which provider resource to add the secret to.
     * Whether it is *allowed* to is the owner's settings, checked elsewhere.
     */
    public boolean isRotatable() {
        return integrationId != null && providerResourceId != null;
    }

    public Long getIntegrationId() {
        return integrationId;
    }

    public String getProviderResourceId() {
        return providerResourceId;
    }

    public String getProviderCredentialId() {
        return providerCredentialId;
    }

    public void setProviderCredentialId(String providerCredentialId) {
        this.providerCredentialId = providerCredentialId;
    }

    public Boolean getAutoRotate() {
        return autoRotate;
    }

    public void setAutoRotate(Boolean autoRotate) {
        this.autoRotate = autoRotate;
    }

    public Long getRotatedFromKeyId() {
        return rotatedFromKeyId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getRotationFailedAt() {
        return rotationFailedAt;
    }

    public String getRotationFailure() {
        return rotationFailure;
    }

    /** True once the portal has deleted this credential at the provider; the value no longer works anywhere. */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Records that the portal deleted this credential at the provider. Set once. */
    public void markRevoked(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    /** Where a provisioned or rotated key came from; set once, at creation. */
    public void recordProvenance(Long integrationId, String providerResourceId, Boolean autoRotate,
            Long rotatedFromKeyId) {
        this.integrationId = integrationId;
        this.providerResourceId = providerResourceId;
        this.autoRotate = autoRotate;
        this.rotatedFromKeyId = rotatedFromKeyId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public void setKeyValue(String keyValue) {
        this.keyValue = keyValue;
    }

    /**
     * Masked version of the value that is safe to render, e.g. {@code ••••••••3f9a}.
     * Only the last four characters are shown, and only for values long enough that
     * doing so doesn't give much away.
     */
    public String getMaskedValue() {
        if (keyValue == null || keyValue.length() < 12) {
            return MASK;
        }
        return MASK + keyValue.substring(keyValue.length() - 4);
    }

    public LocalDate getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(LocalDate createdOn) {
        this.createdOn = createdOn;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public void setExpiresOn(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public ExpirationStatus getExpirationStatus() {
        return ExpirationStatus.of(expiresOn, LocalDate.now());
    }

    /**
     * Days until the key expires, negative if it already has, or {@code null} if it never expires.
     */
    public Long getDaysUntilExpiry() {
        return expiresOn == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), expiresOn);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isExpiryNotificationsMuted() {
        return expiryNotificationsMuted;
    }

    public void setExpiryNotificationsMuted(boolean expiryNotificationsMuted) {
        this.expiryNotificationsMuted = expiryNotificationsMuted;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastRevealedAt() {
        return lastRevealedAt;
    }

    public UserAccount getOwner() {
        return owner;
    }

    public UserGroup getGroup() {
        return group;
    }

    /** Puts the key in a group by way of its owner. */
    public void assignTo(UserAccount owner, UserGroup group) {
        this.owner = owner;
        this.group = group;
    }

    /** Name of the person who added the key, for the group's key list. */
    public String getOwnerName() {
        return owner == null ? "Unassigned" : owner.getDisplayName();
    }

    public Long getOwnerId() {
        return owner == null ? null : owner.getId();
    }

    public boolean isOwnedBy(UserAccount user) {
        return owner != null && user != null && Objects.equals(owner.getId(), user.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApiKey)) {
            return false;
        }
        ApiKey other = (ApiKey) o;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        // never include the key value here, this ends up in logs
        return "ApiKey{id=" + id + ", name='" + name + "', serviceName='" + serviceName + "'}";
    }
}
