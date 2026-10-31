package com.sidbhat.apikeyportal.invite;

/**
 * An invitation couldn't be issued. The message is shown to the administrator
 * who tried, so it says what to do about it.
 */
public class InvitationException extends RuntimeException {

    public InvitationException(String message) {
        super(message);
    }
}
