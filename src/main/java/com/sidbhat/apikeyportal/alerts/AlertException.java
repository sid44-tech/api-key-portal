package com.sidbhat.apikeyportal.alerts;

/** A chat webhook that could not be used, with a message fit to show the admin. */
public class AlertException extends RuntimeException {

    public AlertException(String message) {
        super(message);
    }

    public AlertException(String message, Throwable cause) {
        super(message, cause);
    }
}
