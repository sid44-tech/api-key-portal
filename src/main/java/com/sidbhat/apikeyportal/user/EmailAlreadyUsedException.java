package com.sidbhat.apikeyportal.user;

/**
 * Thrown when an email address is already registered. The message is shown to the
 * user, so it deliberately says no more than "this one is taken".
 */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException(String email) {
        super("An account with the email address " + email + " already exists.");
    }
}
