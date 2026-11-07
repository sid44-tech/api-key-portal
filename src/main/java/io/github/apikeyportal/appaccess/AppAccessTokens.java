package io.github.apikeyportal.appaccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The secret an application presents. {@code kpa_} plus 256 bits of randomness,
 * URL-safe base64. As with invitation links, only the SHA-256 is stored: the
 * input is full-entropy random, so a fast hash is safe, and lookup has to go
 * straight to a row by hash. The prefix makes a leaked token recognisable to
 * secret scanners and to whoever finds it in a log.
 */
final class AppAccessTokens {

    static final String PREFIX = "kpa_";
    private static final int TOKEN_BYTES = 32;
    /** How much of the token is kept in the clear to tell tokens apart: the prefix plus 8 characters. */
    private static final int SHOWN_LENGTH = PREFIX.length() + 8;
    private static final Pattern SHAPE = Pattern.compile("kpa_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AppAccessTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Rejects anything that cannot be a token before it costs a database lookup. */
    static boolean looksLikeToken(String candidate) {
        return candidate != null && SHAPE.matcher(candidate).matches();
    }

    static String shownPart(String token) {
        return token.substring(0, SHOWN_LENGTH);
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
