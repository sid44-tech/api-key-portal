package com.sidbhat.apikeyportal.sso;

/**
 * The connection could not be saved or used. The message is shown to the super
 * admin who is configuring it, so it says what to do about it.
 */
public class SsoException extends RuntimeException {

    public SsoException(String message) {
        super(message);
    }

    public SsoException(String message, Throwable cause) {
        super(message, cause);
    }
}
