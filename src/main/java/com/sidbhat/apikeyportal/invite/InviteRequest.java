package com.sidbhat.apikeyportal.invite;

import com.sidbhat.apikeyportal.user.UserRole;

/**
 * What an administrator fills in on the invite page: who to invite, what they
 * will be, and which group they join.
 */
public record InviteRequest(String email, UserRole role, Long groupId) {
}
