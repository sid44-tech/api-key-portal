package io.github.apikeyportal.integration;

/**
 * The provider rotated a key in place - revoking the old value - but the portal
 * could not store the new one. Unlike a refusal, the key is now broken until
 * the next sweep recovers it, so whoever handles this must tell people.
 */
public class RotationNotSavedException extends ProvisioningException {

    public RotationNotSavedException(String message, Throwable cause) {
        super(message, cause);
    }
}
