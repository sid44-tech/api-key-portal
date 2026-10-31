package com.sidbhat.apikeyportal.integration;

import java.time.Instant;
import java.util.Objects;

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
 * A connection to an external service provider that the portal can create
 * credentials in on the user's behalf, e.g. an Azure AD tenant.
 * <p>
 * The provider-side credential ({@link #getClientSecret()}) is the most sensitive
 * value the portal stores - it can create new credentials - so it is encrypted
 * at rest and never rendered back into a page.
 */
@Entity
@Table(name = "service_integration")
@EntityListeners(AuditingEntityListener.class)
public class ServiceIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ServiceProvider provider;

    /** Display name, also used as the service name on keys created through this integration. */
    @Column(nullable = false, length = 100)
    private String name;

    /** Azure AD: directory (tenant) ID or domain. Google Cloud: project ID. */
    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    /** Azure AD: application (client) ID of the portal's own app registration. Google Cloud: service account email. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Azure AD: client secret. Google Cloud: service account private key (PEM). Encrypted at rest. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "client_secret", nullable = false, length = 8192)
    private String clientSecret;

    /**
     * The group that set the integration up. Stored credentials can mint new
     * credentials in the provider, so only that group may use or see it.
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

    /** Last time the stored credentials successfully authenticated against the provider. */
    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    protected ServiceIntegration() {
        // for JPA
    }

    public ServiceIntegration(ServiceProvider provider, String name, String tenantId, String clientId,
            String clientSecret) {
        this.provider = provider;
        this.name = name;
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public Long getId() {
        return id;
    }

    public ServiceProvider getProvider() {
        return provider;
    }

    public String getName() {
        return name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public UserGroup getGroup() {
        return group;
    }

    public void assignTo(UserGroup group) {
        this.group = group;
    }

    public Long getGroupId() {
        return group == null ? null : group.getId();
    }

    public void markVerified(Instant verifiedAt) {
        this.lastVerifiedAt = verifiedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceIntegration)) {
            return false;
        }
        ServiceIntegration other = (ServiceIntegration) o;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        // never include the client secret here, this ends up in logs
        return "ServiceIntegration{id=" + id + ", provider=" + provider + ", name='" + name
                + "', tenantId='" + tenantId + "'}";
    }
}
