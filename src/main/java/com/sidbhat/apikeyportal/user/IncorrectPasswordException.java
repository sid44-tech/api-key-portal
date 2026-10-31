package com.sidbhat.apikeyportal.user;

/**
 * Thrown when the current password given on the change-password page doesn't match.
 */
public class IncorrectPasswordException extends RuntimeException {

    public IncorrectPasswordException() {
        super("That is not your current password.");
    }
}
