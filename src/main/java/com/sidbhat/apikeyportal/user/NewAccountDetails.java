package com.sidbhat.apikeyportal.user;

/**
 * What it takes to create an account. The name and password come from the person
 * accepting an invitation; the email, role and group come from the invitation
 * itself, so they are the inviting administrator's choice rather than theirs.
 */
public record NewAccountDetails(String email, String displayName, String password, UserRole role, UserGroup group) {

    @Override
    public String toString() {
        // never include the password here, this ends up in logs
        return "NewAccountDetails{email='" + email + "', role=" + role + ", group=" + group + "}";
    }
}
