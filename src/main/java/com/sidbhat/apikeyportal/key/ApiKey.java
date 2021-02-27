package com.sidbhat.apikeyportal.key;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;

import com.sidbhat.apikeyportal.crypto.EncryptedStringConverter;

/**
 * A single API / access key for a third-party service.
 */
@Entity
@Table(name = "api_key")
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

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

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

    @PrePersist
    void onPrePersist() {
        if (addedAt == null) {
            addedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
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

    public Instant getAddedAt() {
        return addedAt;
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
