package io.github.apikeyportal.integration.aws;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.github.apikeyportal.integration.ProvisioningException;

/**
 * The slice of the AWS IAM API this portal needs: create a user, give it an
 * access key, delete a user again after a failed run - plus STS's "who am I",
 * which is the connection test. Query protocol over POST, SigV4-signed by
 * {@link AwsRequestSigner}, XML back.
 * <p>
 * Users are created under the path {@code /keyportal/}, which is what lets the
 * wizard's suggested policy be least-privilege: the portal's own credential can
 * only manage users under that path, never anything else in the account.
 * <p>
 * The responses are flat, attribute-free XML from a fixed, versioned API, read
 * here with per-tag patterns rather than an XML parser - the same trade the
 * Google client makes reading error JSON with patterns, and one that keeps XML
 * external-entity machinery out of the portal entirely.
 */
@Component
public class AwsIamClient {

    /** Both global endpoints sign as this region, whatever the account uses. */
    static final String REGION = "us-east-1";

    /** Everything the portal creates lives under here; the wizard's policy is scoped to it. */
    static final String USER_PATH = "/keyportal/";

    private static final String IAM_VERSION = "2010-05-08";
    private static final String STS_VERSION = "2011-06-15";
    private static final String FORM = "application/x-www-form-urlencoded; charset=utf-8";
    private static final int MAX_DETAIL_LENGTH = 300;

    private static final Pattern ERROR_CODE = tag("Code");
    private static final Pattern ERROR_MESSAGE = tag("Message");

    private final RestClient iamClient;
    private final RestClient stsClient;
    private final String iamHost;
    private final String stsHost;

    @Autowired
    public AwsIamClient(AwsProperties properties) {
        this(defaultBuilder(), properties);
    }

    AwsIamClient(RestClient.Builder builder, AwsProperties properties) {
        this.iamClient = builder.clone().baseUrl(properties.iamBaseUrl()).build();
        this.stsClient = builder.clone().baseUrl(properties.stsBaseUrl()).build();
        this.iamHost = URI.create(properties.iamBaseUrl()).getHost();
        this.stsHost = URI.create(properties.stsBaseUrl()).getHost();
    }

    private static RestClient.Builder defaultBuilder() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /**
     * Asks STS who these credentials belong to, which is the connection test:
     * {@code GetCallerIdentity} needs no permissions at all, so it proves the key
     * pair is real and current without proving anything else works.
     *
     * @return the twelve-digit account id the credentials belong to
     */
    public String callerAccount(String accessKeyId, String secretAccessKey) {
        String body = "Action=GetCallerIdentity&Version=" + STS_VERSION;
        String response = call(stsClient, stsHost, "sts", accessKeyId, secretAccessKey, body,
                "AWS rejected the credentials");
        return required(response, "Account", "GetCallerIdentity");
    }

    /** Creates an IAM user under {@link #USER_PATH}. The user itself can do nothing yet. */
    public CreatedUser createUser(String accessKeyId, String secretAccessKey, String userName) {
        String body = form("Action", "CreateUser", "Version", IAM_VERSION,
                "UserName", userName, "Path", USER_PATH);
        String response = call(iamClient, iamHost, "iam", accessKeyId, secretAccessKey, body,
                "Could not create the IAM user");
        return new CreatedUser(required(response, "UserName", "CreateUser"),
                required(response, "Arn", "CreateUser"));
    }

    /**
     * Mints the access key pair for a user. The secret half is in this response
     * and nowhere else, ever again - it goes straight to the encrypted store.
     */
    public CreatedAccessKey createAccessKey(String accessKeyId, String secretAccessKey, String userName) {
        String body = form("Action", "CreateAccessKey", "Version", IAM_VERSION, "UserName", userName);
        String response = call(iamClient, iamHost, "iam", accessKeyId, secretAccessKey, body,
                "Could not create the access key");
        return new CreatedAccessKey(required(response, "AccessKeyId", "CreateAccessKey"),
                required(response, "SecretAccessKey", "CreateAccessKey"));
    }

    /**
     * Deletes one access key of a user, leaving the user and its other key alone.
     * This is what makes a portal-side expiry true for AWS: the replacement pair
     * is already live, and from this call on the old one is not.
     *
     * @return true if the key was deleted, false if AWS no longer had it (already
     *         deleted in the console, say) - either way it is gone, which is the point
     */
    public boolean deleteAccessKey(String accessKeyId, String secretAccessKey, String userName,
            String accessKeyIdToDelete) {
        String body = form("Action", "DeleteAccessKey", "Version", IAM_VERSION,
                "UserName", userName, "AccessKeyId", accessKeyIdToDelete);
        try {
            call(iamClient, iamHost, "iam", accessKeyId, secretAccessKey, body, "Could not delete the access key");
            return true;
        } catch (ProvisioningException e) {
            if (e.getCause() instanceof RestClientResponseException cause
                    && "NoSuchEntity".equals(extract(cause.getResponseBodyAsString(), ERROR_CODE))) {
                return false;
            }
            throw e;
        }
    }

    /** Removes a user created earlier in a run that then failed. */
    public void deleteUser(String accessKeyId, String secretAccessKey, String userName) {
        String body = form("Action", "DeleteUser", "Version", IAM_VERSION, "UserName", userName);
        call(iamClient, iamHost, "iam", accessKeyId, secretAccessKey, body, "Could not delete the IAM user");
    }

    private String call(RestClient client, String host, String service, String accessKeyId,
            String secretAccessKey, String body, String failure) {
        AwsRequestSigner.Signature signature = AwsRequestSigner.sign("POST", host, "/", "", FORM, body,
                REGION, service, accessKeyId, secretAccessKey, Instant.now());
        try {
            return client.post()
                    .uri("/")
                    .header("Content-Type", FORM)
                    .header("X-Amz-Date", signature.amzDate())
                    .header("Authorization", signature.authorization())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw translate(failure, e);
        } catch (RestClientException e) {
            throw new ProvisioningException("Could not reach AWS: " + e.getMessage(), e);
        }
    }

    private static String required(String response, String tagName, String action) {
        String value = extract(response, tagName);
        if (value == null || value.isBlank()) {
            throw new ProvisioningException("AWS's " + action + " response did not include " + tagName);
        }
        return value;
    }

    private static ProvisioningException translate(String action, RestClientResponseException e) {
        String responseBody = e.getResponseBodyAsString();
        String code = extract(responseBody, ERROR_CODE);
        String message = extract(responseBody, ERROR_MESSAGE);
        String friendly = switch (code == null ? "" : code) {
            case "InvalidClientTokenId" -> "AWS does not recognise that access key ID.";
            case "SignatureDoesNotMatch" -> "The secret access key does not match the access key ID.";
            case "AccessDenied", "AccessDeniedException" ->
                    "The portal's IAM user is missing a permission - re-check the policy from the setup steps.";
            case "EntityAlreadyExists" -> "A user with that name already exists in the account.";
            case "LimitExceeded" -> "An IAM limit was reached. An IAM user can hold two access keys at most, "
                    + "so a key may need deleting in the console before another can be made.";
            default -> null;
        };
        String detail = friendly != null ? friendly
                : (message != null && !message.isBlank() ? truncate(message) : null);
        return new ProvisioningException(action + " (HTTP " + e.getStatusCode().value() + ")"
                + (detail != null ? ": " + detail : ""), e);
    }

    private static String form(String... namesAndValues) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(namesAndValues[i]).append('=')
                    .append(URLEncoder.encode(namesAndValues[i + 1], StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private static Pattern tag(String name) {
        return Pattern.compile("<" + name + ">([^<]*)</" + name + ">");
    }

    private static String extract(String xml, String tagName) {
        return extract(xml, tag(tagName));
    }

    private static String extract(String xml, Pattern pattern) {
        if (xml == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(xml);
        // the five entities XML escaping can put into a text value
        return matcher.find() ? matcher.group(1)
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&") : null;
    }

    private static String truncate(String detail) {
        return detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) + "…" : detail;
    }

    /** @param arn kept for the key's notes, so the user is findable in the console */
    public record CreatedUser(String userName, String arn) {
    }

    public record CreatedAccessKey(String accessKeyId, String secretAccessKey) {
    }
}
