package com.sidbhat.apikeyportal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

/**
 * The portal is single-user, so the credentials live in configuration rather than the database.
 */
@ConstructorBinding
@ConfigurationProperties(prefix = "portal.security")
public class SecurityProperties {

    private final String username;

    /** BCrypt hash of the password. */
    private final String passwordHash;

    public SecurityProperties(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
