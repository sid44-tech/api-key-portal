package com.sidbhat.apikeyportal.mfa;

/** A two-factor operation that could not be done, with a message fit for the form. */
public class MfaException extends RuntimeException {

    public MfaException(String message) {
        super(message);
    }
}
