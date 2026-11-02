package com.sidbhat.apikeyportal.integration.aws;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4, the request signing every AWS API call carries.
 * <p>
 * Hand-rolled rather than pulled in with the AWS SDK, for the same reason the
 * Google client hand-rolls its JWT: the portal makes four kinds of call to one
 * service, and the SDK is a dependency tree the size of this application. SigV4
 * is a published, stable algorithm - an HMAC chain over a canonical form of the
 * request - and {@code AwsRequestSignerTest} pins this implementation to the
 * worked example in AWS's own documentation, signature and all.
 * <p>
 * Supports what the portal sends: single-value headers, no payload streaming,
 * an already-canonical query string (ours is always empty - the IAM query
 * protocol is spoken via POST bodies).
 */
final class AwsRequestSigner {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsRequestSigner() {
    }

    /**
     * The two headers that make a request acceptable to AWS.
     *
     * @param amzDate       goes out as {@code X-Amz-Date}
     * @param authorization goes out as {@code Authorization}
     */
    record Signature(String amzDate, String authorization) {
    }

    /**
     * @param contentType the request's Content-Type, or null for none (a GET)
     * @param query       the canonical query string: keys sorted, values encoded.
     *                    Empty for every call this portal makes.
     */
    static Signature sign(String method, String host, String path, String query, String contentType,
            String body, String region, String service, String accessKeyId, String secretAccessKey,
            Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);

        // Canonical headers: lowercase names, sorted; content-type < host < x-amz-date
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        if (contentType != null) {
            canonicalHeaders.append("content-type:").append(contentType).append('\n');
            signedHeaders.append("content-type;");
        }
        canonicalHeaders.append("host:").append(host).append('\n')
                .append("x-amz-date:").append(amzDate).append('\n');
        signedHeaders.append("host;x-amz-date");

        String canonicalRequest = method + '\n'
                + path + '\n'
                + query + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + hexSha256(body == null ? "" : body);

        String scope = dateStamp + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + '\n' + scope + '\n' + hexSha256(canonicalRequest);

        // the signing key is derived, never the secret itself, so the secret
        // never touches the wire in any form
        byte[] signingKey = hmac(hmac(hmac(hmac(
                ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp),
                region), service), "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));

        return new Signature(amzDate, "AWS4-HMAC-SHA256 Credential=" + accessKeyId + '/' + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
    }

    private static String hexSha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is guaranteed on every JVM", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is guaranteed on every JVM", e);
        }
    }
}
