package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Backing object for the accept-invitation page.
 * <p>
 * Only a name and a password: the email, role and group are fixed by the
 * invitation, so there is nothing here for someone to inflate their own access
 * with.
 */
public class AcceptInviteForm {

    @NotBlank
    @Size(max = 100)
    private String displayName;

    @NotBlank
    @Size(min = UserAccountService.MIN_PASSWORD_LENGTH, max = 200,
            message = "must be at least " + UserAccountService.MIN_PASSWORD_LENGTH + " characters")
    private String password;

    @NotBlank
    private String confirmPassword;

    @AssertTrue(message = "must match the password above")
    public boolean isConfirmPasswordMatching() {
        return password == null || password.equals(confirmPassword);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
