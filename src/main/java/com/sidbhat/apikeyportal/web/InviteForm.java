package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.sidbhat.apikeyportal.invite.InviteRequest;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Backing object for the invite-user page.
 */
public class InviteForm {

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotNull
    private UserRole role = UserRole.MEMBER;

    /** A group admin only ever gets their own; the service re-checks that. */
    @NotNull(message = "pick a group for the new account")
    private Long groupId;

    public InviteRequest toRequest() {
        return new InviteRequest(email, role, groupId);
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }
}
