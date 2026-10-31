package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Backing object for the change-password page.
 */
public class PasswordChangeForm {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = UserAccountService.MIN_PASSWORD_LENGTH, max = 200,
            message = "must be at least " + UserAccountService.MIN_PASSWORD_LENGTH + " characters")
    private String newPassword;

    @NotBlank
    private String confirmPassword;

    @AssertTrue(message = "must match the new password above")
    public boolean isConfirmPasswordMatching() {
        return newPassword == null || newPassword.equals(confirmPassword);
    }

    @AssertTrue(message = "must be different from your current password")
    public boolean isNewPasswordDifferent() {
        return newPassword == null || !newPassword.equals(currentPassword);
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
