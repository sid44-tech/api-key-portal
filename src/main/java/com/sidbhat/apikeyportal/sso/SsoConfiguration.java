package com.sidbhat.apikeyportal.sso;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

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
import com.sidbhat.apikeyportal.user.UserGroup;

/**
 * The organisation's identity provider, as configured by the super admin.
 * <p>
 * Only the issuer is stored, not the individual endpoints: everything else comes
 * from the provider's OpenID Connect discovery document, so the admin pastes
 * three values instead of six and the portal picks up key rotations by itself.
 */
@Entity
@Table(name = "sso_configuration")
@EntityListeners(AuditingEntityListener.class)
public class SsoConfiguration {

    /** What the portal asks for unless the admin widens it. */
    public static final String DEFAULT_SCOPES = "openid,profile,email";

    /** Entra ID, Okta and Google all use this claim name by default. */
    public static final String DEFAULT_GROUPS_CLAIM = "groups";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sign-in is only offered while this is on; turning it off never deletes anything. */
    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(name = "issuer_uri", nullable = false, length = 500)
    private String issuerUri;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    /** Encrypted at rest, like an integration's credential. Never rendered back into a page. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "client_secret", nullable = false, length = 8192)
    private String clientSecret;

    /** Comma separated. {@code openid} is always included whatever is stored. */
    @Column(nullable = false, length = 300)
    private String scopes;

    @Column(name = "groups_claim", nullable = false, length = 100)
    private String groupsClaim;

    /** Somebody must be in this group at the provider before the portal will let them in. */
    @Column(name = "required_group", nullable = false, length = 255)
    private String requiredGroup;

    /** Optional. Membership additionally makes somebody a group admin. */
    @Column(name = "admin_group", length = 255)
    private String adminGroup;

    /** The portal group accounts created through SSO join. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "default_group_id")
    private UserGroup defaultGroup;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SsoConfiguration() {
        // for JPA
    }

    public SsoConfiguration(String displayName, String issuerUri, String clientId, String clientSecret) {
        this.displayName = displayName;
        this.issuerUri = issuerUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = DEFAULT_SCOPES;
        this.groupsClaim = DEFAULT_GROUPS_CLAIM;
    }

    public Long getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    /** {@code openid} is mandatory for OIDC, so it goes in whether or not it was typed. */
    public Set<String> scopeSet() {
        Set<String> all = new LinkedHashSet<>();
        all.add("openid");
        Arrays.stream(scopes.split(","))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .forEach(all::add);
        return all;
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public void setGroupsClaim(String groupsClaim) {
        this.groupsClaim = groupsClaim;
    }

    public String getRequiredGroup() {
        return requiredGroup;
    }

    public void setRequiredGroup(String requiredGroup) {
        this.requiredGroup = requiredGroup;
    }

    public String getAdminGroup() {
        return adminGroup;
    }

    public void setAdminGroup(String adminGroup) {
        this.adminGroup = adminGroup;
    }

    public UserGroup getDefaultGroup() {
        return defaultGroup;
    }

    public void setDefaultGroup(UserGroup defaultGroup) {
        this.defaultGroup = defaultGroup;
    }

    public Long getDefaultGroupId() {
        return defaultGroup == null ? null : defaultGroup.getId();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        // never include the client secret here, this ends up in logs
        return "SsoConfiguration{issuer='" + issuerUri + "', clientId='" + clientId
                + "', enabled=" + enabled + "}";
    }
}
