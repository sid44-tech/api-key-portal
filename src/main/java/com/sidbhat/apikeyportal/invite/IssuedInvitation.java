package com.sidbhat.apikeyportal.invite;

/**
 * An invitation that has just been created, together with the one and only copy
 * of its link. The link is not recoverable afterwards - only the token's hash is
 * kept - so if the email didn't go out, this is the administrator's chance to
 * pass it on by hand.
 *
 * @param emailed whether the link was successfully emailed to the invitee
 */
public record IssuedInvitation(UserInvitation invitation, String link, boolean emailed) {
}
