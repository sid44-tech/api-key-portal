package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.sidbhat.apikeyportal.user.ProfileDetails;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * Backing object for the profile page. Role and group are deliberately absent -
 * those are an administrator's to change, not your own.
 */
public class ProfileForm {

    @NotBlank
    @Size(max = 100)
    private String displayName;

    /** Also the login identifier, so changing it changes how you sign in. */
    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 100)
    private String jobTitle;

    public static ProfileForm from(UserAccount account) {
        ProfileForm form = new ProfileForm();
        form.displayName = account.getDisplayName();
        form.email = account.getEmail();
        form.jobTitle = account.getJobTitle();
        return form;
    }

    public ProfileDetails toDetails() {
        return new ProfileDetails(email, displayName, jobTitle);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }
}
