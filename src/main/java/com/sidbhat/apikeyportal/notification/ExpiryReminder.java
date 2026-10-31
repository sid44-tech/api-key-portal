package com.sidbhat.apikeyportal.notification;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A reminder that was actually sent, so it is never sent twice.
 * <p>
 * Keyed on the key's expiry date as well as the key, deliberately: change the
 * expiry and the reminders start over for the new date, which is what somebody
 * rotating a key for another year would expect.
 */
@Entity
@Table(name = "expiry_reminder")
public class ExpiryReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "api_key_id", nullable = false)
    private Long apiKeyId;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    /** Stored as the number of days, so the row reads sensibly in the database. */
    @Column(name = "threshold_days", nullable = false)
    private int thresholdDays;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected ExpiryReminder() {
        // for JPA
    }

    public ExpiryReminder(Long apiKeyId, LocalDate expiresOn, ReminderThreshold threshold, String recipientEmail,
            Instant sentAt) {
        this.apiKeyId = apiKeyId;
        this.expiresOn = expiresOn;
        this.thresholdDays = threshold.getDays();
        this.recipientEmail = recipientEmail;
        this.sentAt = sentAt;
    }

    public Long getId() {
        return id;
    }

    public Long getApiKeyId() {
        return apiKeyId;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public int getThresholdDays() {
        return thresholdDays;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
