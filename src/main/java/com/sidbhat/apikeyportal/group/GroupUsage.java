package com.sidbhat.apikeyportal.group;

import com.sidbhat.apikeyportal.user.UserGroup;

/**
 * A group and everything currently pointing at it. Shown on the groups page so a
 * super admin can see what a group holds before trying to remove it.
 *
 * @param invitations invitations that could still be accepted into this group
 */
public record GroupUsage(UserGroup group, long members, long keys, long integrations, long invitations) {

    /** Nothing refers to it, so it can be deleted without stranding anything. */
    public boolean isEmpty() {
        return members == 0 && keys == 0 && integrations == 0 && invitations == 0;
    }
}
