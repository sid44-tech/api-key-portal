package com.sidbhat.apikeyportal.rotation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.sidbhat.apikeyportal.integration.ServiceProvider;

/**
 * One person's choice about one provider: whether the portal may mint
 * replacements for their expiring keys there.
 * <p>
 * No row means no: minting credentials in a provider on somebody's behalf is
 * strictly opt-in. A key can still pin itself on or off individually -
 * {@code ApiKey.autoRotate} overrides this where it is set.
 */
@Entity
@Table(name = "auto_rotation_preference")
@EntityListeners(AuditingEntityListener.class)
public class AutoRotationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceProvider provider;

    @Column(nullable = false)
    private boolean enabled;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutoRotationPreference() {
        // for JPA
    }

    public AutoRotationPreference(Long userId, ServiceProvider provider, boolean enabled) {
        this.userId = userId;
        this.provider = provider;
        this.enabled = enabled;
    }

    public Long getUserId() {
        return userId;
    }

    public ServiceProvider getProvider() {
        return provider;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "AutoRotationPreference{user=" + userId + ", provider=" + provider + ", enabled=" + enabled + "}";
    }
}
