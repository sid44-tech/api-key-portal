package com.sidbhat.apikeyportal.user;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import jakarta.persistence.Column;
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

/**
 * Someone who can sign in to the portal.
 */
@Entity
@Table(name = "user_account")
@EntityListeners(AuditingEntityListener.class)
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login identifier. Stored lower-cased so sign-in is case-insensitive. */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** BCrypt hash. The plaintext password never reaches the database. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    /**
     * The group whose keys this user can see. Eager because practically every
     * page that loads an account also shows or checks the group.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id")
    private UserGroup group;

    /** Free-form, shown to the rest of the group so people know who holds which key. */
    @Column(name = "job_title", length = 100)
    private String jobTitle;

    /** A disabled account keeps its keys but can no longer sign in. */
    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Set on the super admin seeded with the default password. While it is true the
     * portal lets the account do nothing but replace that password.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * The OIDC subject this account signs in as, once it has been linked to the
     * organisation's identity provider. Null for accounts that only use a password.
     * The subject is used rather than the email because it survives a rename at
     * the provider, which the email does not.
     */
    @Column(name = "sso_subject", length = 255)
    private String ssoSubject;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set by {@link UserAccountRepository#recordLogin} only, so it doesn't count as a modification. */
    @Column(name = "last_login_at", insertable = false, updatable = false)
    private Instant lastLoginAt;

    protected UserAccount() {
        // for JPA
    }

    public UserAccount(String email, String displayName, String passwordHash, UserRole role, UserGroup group) {
        this.email = normalizeEmail(email);
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.group = group;
    }

    /** Emails are compared and stored lower-cased; the user may type any casing. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public UserGroup getGroup() {
        return group;
    }

    public void setGroup(UserGroup group) {
        this.group = group;
    }

    public Long getGroupId() {
        return group == null ? null : group.getId();
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public String getSsoSubject() {
        return ssoSubject;
    }

    public void setSsoSubject(String ssoSubject) {
        this.ssoSubject = ssoSubject;
    }

    /** True once this account signs in through the identity provider. */
    public boolean isSsoLinked() {
        return ssoSubject != null;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public boolean isSuperAdmin() {
        return role == UserRole.SUPER_ADMIN;
    }

    /** True if this user administers {@code other}'s group, or administers everything. */
    public boolean administers(UserAccount other) {
        if (isSuperAdmin()) {
            return true;
        }
        if (other.isSuperAdmin()) {
            // a group admin who shares a group with a super admin must not be able to
            // demote or lock out the one account that administers the whole portal
            return false;
        }
        return role == UserRole.GROUP_ADMIN && isInSameGroupAs(other);
    }

    public boolean isInSameGroupAs(UserAccount other) {
        return getGroupId() != null && Objects.equals(getGroupId(), other.getGroupId());
    }

    public boolean is(UserAccount other) {
        return other != null && id != null && Objects.equals(id, other.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserAccount)) {
            return false;
        }
        UserAccount other = (UserAccount) o;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        // never include the password hash here, this ends up in logs
        return "UserAccount{id=" + id + ", email='" + email + "', role=" + role + ", group=" + getGroupId() + "}";
    }
}
