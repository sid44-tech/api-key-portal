package com.sidbhat.apikeyportal.invite;

/**
 * The link someone followed is not one they can use: unknown, already used,
 * withdrawn, or past its day.
 * <p>
 * One exception covers all four on purpose. The accept page is public, so
 * telling an anonymous visitor which of those it was would confirm that a given
 * token once existed, or that a given address was invited.
 */
public class InvalidInvitationException extends RuntimeException {

    public InvalidInvitationException(String message) {
        super(message);
    }
}
