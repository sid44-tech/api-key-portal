package com.sidbhat.apikeyportal.security;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Seed credentials for the super admin. Accounts live in the database, so these
 * are only read while the portal is empty - see {@link BootstrapAdminInitializer}.
 * <p>
 * A fresh install always gets this one account, because sign-up is closed and
 * every other account arrives by invitation from someone who is already in.
 */
@ConfigurationProperties(prefix = "portal.security")
@Validated
public class SecurityProperties {

    /** Login identifier of the seeded super admin. An email address, since that is what sign-in uses. */
    @NotBlank
    private final String adminEmail;

    /**
     * Password the seeded super admin starts with. Has to be replaced at their
     * first sign-in, so the shipped default is a placeholder, not a secret.
     */
    @NotBlank
    private final String adminPassword;

    /**
     * BCrypt hash of the seeded super admin's password, for deployments that would
     * rather not put a plaintext password in configuration at all. Set, it wins over
     * {@link #getAdminPassword()} and is taken as deliberate, so no change is forced.
     */
    private final String adminPasswordHash;

    /** Name of the group the seeded super admin administers. */
    @NotBlank
    private final String adminGroupName;

    public SecurityProperties(String adminEmail, String adminPassword, String adminPasswordHash,
            String adminGroupName) {
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminPasswordHash = adminPasswordHash;
        this.adminGroupName = adminGroupName;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public String getAdminPasswordHash() {
        return adminPasswordHash;
    }

    public String getAdminGroupName() {
        return adminGroupName;
    }
}
