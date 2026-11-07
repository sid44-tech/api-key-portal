package io.github.apikeyportal.appaccess;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserGroup;

/**
 * A credential an application presents to read the current value of specific
 * keys. Belongs to a group, like an integration: group admins and super admins
 * manage it, the group's members can see that it exists. The token itself is
 * never stored - only its SHA-256 - so not even an admin can see it again.
 */
@Entity
@Table(name = "app_access_token")
public class AppAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private UserGroup group;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_prefix", nullable = false, length = 12)
    private String tokenPrefix;

    /** Plain id and name, not a relation: the token outlives the account that made it. */
    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", nullable = false, length = 100)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @OneToMany(mappedBy = "token", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id")
    private List<AppAccessGrant> grants = new ArrayList<>();

    protected AppAccessToken() {
        // for JPA
    }

    AppAccessToken(UserGroup group, String name, String tokenHash, String tokenPrefix, UserAccount creator,
            Instant createdAt, Instant expiresAt) {
        this.group = group;
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.createdById = creator.getId();
        this.createdByName = creator.getDisplayName();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    void grant(Long keyId) {
        grants.add(new AppAccessGrant(this, keyId));
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

    public String getName() {
        return name;
    }

    String getTokenHash() {
        return tokenHash;
    }

    /** The first characters of the token, safe to show: enough to tell tokens apart, useless to an attacker. */
    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public Long getCreatedById() {
        return createdById;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    public List<AppAccessGrant> getGrants() {
        return grants;
    }

    @Override
    public String toString() {
        // never the hash, let alone the token
        return "AppAccessToken{id=" + id + ", name='" + name + "', prefix='" + tokenPrefix + "'}";
    }
}
