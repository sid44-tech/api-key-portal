package io.github.apikeyportal.mfa;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 over RFC 4226: HMAC-SHA1, six digits,
 * thirty-second steps - the defaults every authenticator app ships with, so an
 * enrolment works with whatever the person already has on their phone.
 * <p>
 * Written here rather than pulled in: it is forty lines of specified
 * arithmetic, pinned by the RFC's own test vectors, and a dependency for it
 * would be a larger attack surface than the code it replaced.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;

    /**
     * How many steps either side of now a code is accepted for. One: a phone
     * whose clock is half a minute out still works, and a stolen code is good
     * for ninety seconds at most rather than thirty.
     */
    public static final int ALLOWED_DRIFT_STEPS = 1;

    /** 160 bits, what RFC 4226 recommends and what SHA-1 can use in full. */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A fresh secret, Base32 without padding - the form authenticator apps accept by hand. */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    /** The step the clock is in now. */
    public static long currentStep() {
        return stepOf(Instant.now());
    }

    public static long stepOf(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /**
     * Which step, if any, a submitted code is valid for.
     *
     * @param lastUsedStep the step the account last accepted a code for, so a code
     *                     seen once is never good again; -1 if none yet
     * @return the matching step, or -1 if the code is wrong, replayed or malformed
     */
    public static long matchingStep(String secretBase32, String code, long nowStep, long lastUsedStep) {
        String digits = code == null ? "" : code.replaceAll("[\\s-]", "");
        if (digits.length() != DIGITS || !digits.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        byte[] key = decodeBase32(secretBase32);
        // every candidate is computed and compared whatever happens, so timing
        // says nothing about which step - if any - matched
        long matched = -1;
        for (long step = nowStep - ALLOWED_DRIFT_STEPS; step <= nowStep + ALLOWED_DRIFT_STEPS; step++) {
            boolean equal = MessageDigest.isEqual(codeFor(key, step).getBytes(StandardCharsets.US_ASCII),
                    digits.getBytes(StandardCharsets.US_ASCII));
            if (equal && step > lastUsedStep && matched < 0) {
                matched = step;
            }
        }
        return matched;
    }

    /** The code for one step, zero-padded to six digits. Package-private for tests and enrolment previews. */
    static String codeFor(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }

    static String codeFor(String secretBase32, long step) {
        return codeFor(decodeBase32(secretBase32), step);
    }

    /**
     * The URI an authenticator app reads from the QR code. The issuer is in both
     * places the spec allows, which is what makes the entry sort under the
     * portal's name rather than under the email.
     */
    public static String otpauthUri(String issuer, String accountEmail, String secretBase32) {
        String label = encode(issuer) + ":" + encode(accountEmail);
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String text) {
        String clean = text.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("not a Base32 secret");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
            }
        }
        return out.array();
    }
}
