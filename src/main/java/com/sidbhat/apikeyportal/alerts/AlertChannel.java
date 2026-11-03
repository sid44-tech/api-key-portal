package com.sidbhat.apikeyportal.alerts;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import com.sidbhat.apikeyportal.user.UserGroup;

/**
 * A group's chat channel: where its alerts go, in which product, and which ones.
 * <p>
 * The webhook URL is the credential: anyone holding it can post into the
 * channel as the portal. Encrypted at rest, never rendered back into a page.
 */
@Entity
@Table(name = "alert_channel")
@EntityListeners(AuditingEntityListener.class)
public class AlertChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private UserGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelProvider provider;

    /** How the admin refers to it - "#platform-alerts" - shown on the integrations page. */
    @Column(nullable = false, length = 60)
    private String name;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "webhook_url", nullable = false, length = 8192)
    private String webhookUrl;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "notify_expiries", nullable = false)
    private boolean notifyExpiries = true;

    @Column(name = "notify_rotation_failures", nullable = false)
    private boolean notifyRotationFailures = true;

    /** Off by default: lockouts name accounts and addresses, which not every channel should see. */
    @Column(name = "notify_lockouts", nullable = false)
    private boolean notifyLockouts;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AlertChannel() {
        // for JPA
    }

    public AlertChannel(UserGroup group, ChannelProvider provider, String name, String webhookUrl) {
        this.group = group;
        this.provider = provider;
        this.name = name;
        this.webhookUrl = webhookUrl;
    }

    public Long getId() {
        return id;
    }

    public UserGroup getGroup() {
        return group;
    }

    public Long getGroupId() {
        return group == null ? null : group.getId();
    }

    public ChannelProvider getProvider() {
        return provider;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isNotifyExpiries() {
        return notifyExpiries;
    }

    public void setNotifyExpiries(boolean notifyExpiries) {
        this.notifyExpiries = notifyExpiries;
    }

    public boolean isNotifyRotationFailures() {
        return notifyRotationFailures;
    }

    public void setNotifyRotationFailures(boolean notifyRotationFailures) {
        this.notifyRotationFailures = notifyRotationFailures;
    }

    public boolean isNotifyLockouts() {
        return notifyLockouts;
    }

    public void setNotifyLockouts(boolean notifyLockouts) {
        this.notifyLockouts = notifyLockouts;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public String getLastError() {
        return lastError;
    }

    /** A post went through: the channel is known to work, and any earlier error is history. */
    public void markSent(Instant at) {
        this.lastSentAt = at;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.lastError = error == null ? "Failed without a message"
                : error.length() <= 500 ? error : error.substring(0, 497) + "...";
    }

    @Override
    public String toString() {
        // never the webhook URL: it ends up in logs, and it is the credential
        return "AlertChannel{id=" + id + ", provider=" + provider + ", group=" + getGroupId() + ", name='" + name + "'}";
    }
}
