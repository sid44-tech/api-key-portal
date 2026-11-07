package io.github.apikeyportal.appaccess;

/**
 * The read API refused a request. Carries the HTTP status and a short,
 * machine-readable code; the message is safe to send back to the caller and
 * never says more than it has to.
 */
public class AppAccessException extends RuntimeException {

    private final int status;
    private final String code;

    public AppAccessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    static AppAccessException disabled() {
        return new AppAccessException(403, "api_access_disabled", "Application access is switched off on this portal");
    }

    static AppAccessException invalidToken() {
        return new AppAccessException(401, "invalid_token", "The token is missing, unknown or expired");
    }

    static AppAccessException unknownReference() {
        return new AppAccessException(404, "unknown_reference", "This token has no key with that reference");
    }
}
