package com.sidbhat.apikeyportal.user;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed-in user as Spring Security sees them: a snapshot of the account
 * taken at login, held in the session.
 * <p>
 * Deliberately not the {@link UserAccount} entity - a detached entity in the
 * session would quietly go stale. Anything that needs current data reloads the
 * account by {@link #getId()}; a role or group change therefore takes effect on
 * the user's next sign-in.
 */
public class PortalUser implements UserDetails, Serializable {

    private final Long id;
    private final String email;
    private final String displayName;
    private final String passwordHash;
    private final UserRole role;
    private final boolean enabled;
    private final boolean mustChangePassword;
    private final boolean ssoLinked;

    public PortalUser(UserAccount account) {
        this.id = account.getId();
        this.email = account.getEmail();
        this.displayName = account.getDisplayName();
        this.passwordHash = account.getPasswordHash();
        this.role = account.getRole();
        this.enabled = account.isEnabled();
        this.mustChangePassword = account.isMustChangePassword();
        this.ssoLinked = account.isSsoLinked();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    /** Shown in the navigation bar. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Up to two initials for the avatar in the navigation bar - "Alex Carter"
     * becomes "AC". Falls back to the email's first letter for a one-word name
     * that is somehow blank, so the avatar is never empty.
     */
    public String getInitials() {
        String source = displayName == null || displayName.isBlank() ? email : displayName;
        if (source == null || source.isBlank()) {
            return "?";
        }
        String[] words = source.trim().split("\\s+");
        String initials = words[0].substring(0, 1);
        if (words.length > 1) {
            initials += words[words.length - 1].substring(0, 1);
        }
        return initials.toUpperCase(Locale.ROOT);
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.getAuthority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True when this person signs in through the organisation's identity provider,
     * in which case there is no portal password for them to manage.
     */
    public boolean isSsoLinked() {
        return ssoLinked;
    }

    /**
     * True while the account is still on the password it was handed. Cleared by
     * changing it, which also ends the session, so this snapshot never goes stale.
     */
    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    @Override
    public String toString() {
        // never include the password hash here, this ends up in logs
        return "PortalUser{id=" + id + ", email='" + email + "', role=" + role + "}";
    }
}
