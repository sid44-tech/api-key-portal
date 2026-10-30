package com.sidbhat.apikeyportal.integration;

/**
 * A call to an external service provider failed. The message is safe to show to the user.
 */
public class ProvisioningException extends RuntimeException {

    public ProvisioningException(String message) {
        super(message);
    }

    public ProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
