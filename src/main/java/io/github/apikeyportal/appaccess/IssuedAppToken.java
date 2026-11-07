package io.github.apikeyportal.appaccess;

/**
 * A newly created token and its plaintext - the one moment the plaintext exists.
 * Show it once, then let it go.
 */
public record IssuedAppToken(AppAccessToken token, String plaintext) {

    @Override
    public String toString() {
        return "IssuedAppToken{" + token + "}";
    }
}
