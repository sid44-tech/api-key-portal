package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.NotNull;

import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * What an administrator can change about someone else's account.
 */
public class UserAdminForm {

    @NotNull
    private UserRole role;

    /** Only a super admin can actually move an account; the field is read-only for others. */
    private Long groupId;

    private boolean enabled = true;

    public static UserAdminForm from(UserAccount account) {
        UserAdminForm form = new UserAdminForm();
        form.role = account.getRole();
        form.groupId = account.getGroupId();
        form.enabled = account.isEnabled();
        return form;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
