package com.sidbhat.apikeyportal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 Appendix B: the ASCII secret "12345678901234567890", HMAC-SHA1. */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void reproducesTheRfc6238TestVectors() {
        // the RFC lists 8-digit codes; the 6-digit code is the last six
        assertThat(Totp.codeFor(RFC_SECRET, Totp.stepOf(Instant.ofEpochSecond(59L)))).isEqualTo("287082");
        assertThat(Totp.codeFor(RFC_SECRET, Totp.stepOf(Instant.ofEpochSecond(1111111109L)))).isEqualTo("081804");
        assertThat(Totp.codeFor(RFC_SECRET, Totp.stepOf(Instant.ofEpochSecond(1111111111L)))).isEqualTo("050471");
        assertThat(Totp.codeFor(RFC_SECRET, Totp.stepOf(Instant.ofEpochSecond(1234567890L)))).isEqualTo("005924");
        assertThat(Totp.codeFor(RFC_SECRET, Totp.stepOf(Instant.ofEpochSecond(2000000000L)))).isEqualTo("279037");
        assertThat(Totp.codeFor(RFC_SECRET, Totp.stepOf(Instant.ofEpochSecond(20000000000L)))).isEqualTo("353130");
    }

    @Test
    void acceptsTheCurrentStepAndOneEitherSideAndNothingFurther() {
        long now = 1_000_000L;
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeFor(RFC_SECRET, now), now, -1)).isEqualTo(now);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeFor(RFC_SECRET, now - 1), now, -1)).isEqualTo(now - 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeFor(RFC_SECRET, now + 1), now, -1)).isEqualTo(now + 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeFor(RFC_SECRET, now - 2), now, -1)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeFor(RFC_SECRET, now + 2), now, -1)).isEqualTo(-1);
    }

    @Test
    void aCodeAlreadyAcceptedIsNeverAcceptedAgain() {
        long now = 1_000_000L;
        String code = Totp.codeFor(RFC_SECRET, now);

        // accepted once, then the step is burned: the same code, still inside the
        // window, is refused - and so is the previous step's, which is older still
        assertThat(Totp.matchingStep(RFC_SECRET, code, now, now - 1)).isEqualTo(now);
        assertThat(Totp.matchingStep(RFC_SECRET, code, now, now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeFor(RFC_SECRET, now - 1), now, now)).isEqualTo(-1);
        // the next step's code is still fine
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeFor(RFC_SECRET, now + 1), now, now)).isEqualTo(now + 1);
    }

    @Test
    void spacesAndDashesInATypedCodeAreForgivenButNothingElseIs() {
        long now = 1_000_000L;
        String code = Totp.codeFor(RFC_SECRET, now);
        assertThat(Totp.matchingStep(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now, -1)).isEqualTo(now);
        assertThat(Totp.matchingStep(RFC_SECRET, "", now, -1)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, null, now, -1)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, "12345", now, -1)).isEqualTo(-1);
        assertThat(Totp.matchingStep(RFC_SECRET, "abcdef", now, -1)).isEqualTo(-1);
    }

    @Test
    void secretsAreOneHundredAndSixtyBitsOfBase32ThatRoundTrip() {
        String secret = Totp.generateSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.decodeBase32(secret)).hasSize(20);
        assertThat(Totp.base32(Totp.decodeBase32(secret))).isEqualTo(secret);
        assertThat(Totp.generateSecret()).isNotEqualTo(secret);
        // the key typed by hand, in groups and lower case, is the same key
        assertThat(Totp.decodeBase32(secret.toLowerCase().replaceAll("(.{4})", "$1 ")))
                .isEqualTo(Totp.decodeBase32(secret));
    }

    @Test
    void theOtpauthUriIsWhatAnAuthenticatorAppExpects() {
        String uri = Totp.otpauthUri("Keys at Contoso", "sam@example.com", "ABCDEFGH");
        assertThat(uri).isEqualTo("otpauth://totp/Keys%20at%20Contoso:sam%40example.com?secret=ABCDEFGH"
                + "&issuer=Keys%20at%20Contoso&algorithm=SHA1&digits=6&period=30");
    }
}
