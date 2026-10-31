package com.sidbhat.apikeyportal.invite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The secret in an invitation link.
 * <p>
 * A token is 256 bits of randomness, URL-safe base64. Only its SHA-256 is stored,
 * the way a password would be - the link in the email is the only copy of the
 * token itself. Plain SHA-256 rather than BCrypt is deliberate and safe here:
 * the input is full-entropy random, not a guessable password, and lookup has to
 * go straight to a row by hash.
 */
final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private InvitationTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
