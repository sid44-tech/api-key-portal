package io.github.apikeyportal.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Pins the signer to the worked example in AWS's own Signature Version 4
 * documentation ("Create a signed AWS API request"): same request, same
 * credentials, same timestamp, down to the published signature. If this test
 * passes, the HMAC chain, the canonical form and the credential scope are all
 * exactly what AWS computes on its side.
 */
class AwsRequestSignerTest {

    // the documentation's long-published example credentials
    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    @Test
    void reproducesTheDocumentedIamListUsersSignature() {
        AwsRequestSigner.Signature signature = AwsRequestSigner.sign(
                "GET", "iam.amazonaws.com", "/", "Action=ListUsers&Version=2010-05-08",
                "application/x-www-form-urlencoded; charset=utf-8", "",
                "us-east-1", "iam", ACCESS_KEY, SECRET_KEY,
                Instant.parse("2015-08-30T12:36:00Z"));

        assertThat(signature.amzDate()).isEqualTo("20150830T123600Z");
        assertThat(signature.authorization()).isEqualTo(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, "
                        + "SignedHeaders=content-type;host;x-amz-date, "
                        + "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7");
    }

    @Test
    void theSecretNeverAppearsInWhatGoesOnTheWire() {
        AwsRequestSigner.Signature signature = AwsRequestSigner.sign(
                "POST", "iam.amazonaws.com", "/", "", "application/x-www-form-urlencoded; charset=utf-8",
                "Action=CreateUser&UserName=x&Version=2010-05-08",
                "us-east-1", "iam", ACCESS_KEY, SECRET_KEY, Instant.parse("2026-11-01T08:00:00Z"));

        assertThat(signature.authorization()).doesNotContain(SECRET_KEY);
        assertThat(signature.authorization()).contains("Credential=AKIDEXAMPLE/20261101/us-east-1/iam/aws4_request");
    }

    @Test
    void twoDifferentBodiesSignDifferently() {
        Instant now = Instant.parse("2026-11-01T08:00:00Z");
        AwsRequestSigner.Signature one = AwsRequestSigner.sign("POST", "iam.amazonaws.com", "/", "",
                "application/x-www-form-urlencoded; charset=utf-8", "Action=CreateUser&UserName=a",
                "us-east-1", "iam", ACCESS_KEY, SECRET_KEY, now);
        AwsRequestSigner.Signature other = AwsRequestSigner.sign("POST", "iam.amazonaws.com", "/", "",
                "application/x-www-form-urlencoded; charset=utf-8", "Action=CreateUser&UserName=b",
                "us-east-1", "iam", ACCESS_KEY, SECRET_KEY, now);

        // the body hash is inside the signature, so a request cannot be
        // captured and replayed with a different action or user name
        assertThat(one.authorization()).isNotEqualTo(other.authorization());
    }
}
