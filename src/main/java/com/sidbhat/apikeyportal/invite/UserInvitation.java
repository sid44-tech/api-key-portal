package com.sidbhat.apikeyportal.invite;

import java.time.Instant;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * A standing offer of an account, addressed to one email address.
 * <p>
 * The role and group are fixed when the invitation is issued, so accepting one
 * can only ever produce the account the inviting administrator described. The
 * token that proves the invitation is never stored - only its SHA-256 - so
 * someone who can read this table still can't accept anything.
 */
@Entity
@Table(name = "user_invitation")
@EntityListeners(AuditingEntityListener.class)
public class UserInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who it was sent to. Becomes the login identifier of the account. */
    @Column(nullable = false, length = 255)
    private String email;

    /** Hex SHA-256 of the token in the emailed link. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id")
    private UserGroup group;

    /** Kept for the audit trail; shown on the invitations page. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "invited_by_id")
    private UserAccount invitedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set once, when the account is created. A used invitation is never reusable. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** Withdrawn by an administrator before it was used. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected UserInvitation() {
        // for JPA
    }

    public UserInvitation(String email, String tokenHash, UserRole role, UserGroup group, UserAccount invitedBy,
            Instant expiresAt) {
        this.email = UserAccount.normalizeEmail(email);
        this.tokenHash = tokenHash;
        this.role = role;
        this.group = group;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UserRole getRole() {
        return role;
    }

    public UserGroup getGroup() {
        return group;
    }

    public Long getGroupId() {
        return group == null ? null : group.getId();
    }

    public UserAccount getInvitedBy() {
        return invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void accept(Instant at) {
        this.acceptedAt = at;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean hasExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Still usable: not yet accepted, not withdrawn and not past its day. */
    public boolean isPending(Instant now) {
        return !isAccepted() && !isRevoked() && !hasExpired(now);
    }

    public InvitationStatus statusAt(Instant now) {
        if (isAccepted()) {
            return InvitationStatus.ACCEPTED;
        }
        if (isRevoked()) {
            return InvitationStatus.REVOKED;
        }
        return hasExpired(now) ? InvitationStatus.EXPIRED : InvitationStatus.PENDING;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserInvitation)) {
            return false;
        }
        UserInvitation other = (UserInvitation) o;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        // never include the token hash here, this ends up in logs
        return "UserInvitation{id=" + id + ", email='" + email + "', role=" + role + ", group=" + getGroupId() + "}";
    }
}
