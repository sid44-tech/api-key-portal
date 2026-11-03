package com.sidbhat.apikeyportal.integration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.integration.aws.AwsIamClient;
import com.sidbhat.apikeyportal.integration.aws.AwsIamClient.CreatedAccessKey;
import com.sidbhat.apikeyportal.integration.aws.AwsIamClient.CreatedUser;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.integration.cloudflare.CloudflareClient;
import com.sidbhat.apikeyportal.integration.gitlab.GitLabClient;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedApplication;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedSecret;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient.CreatedServiceAccount;
import com.sidbhat.apikeyportal.integration.google.ServiceAccountKey;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;
import com.sidbhat.apikeyportal.key.KeyProvenance;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * Creates a credential in an external provider and stores the result as a local key.
 * <p>
 * For Azure AD this means: app registration + service principal + client secret.
 * The secret value is returned by Azure exactly once and goes straight into the
 * encrypted key store; it is never logged.
 * <p>
 * Deliberately not transactional: the external calls must not hold a database
 * transaction open, and the local save is atomic on its own.
 */
@Service
public class KeyProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(KeyProvisioningService.class);

    /**
     * Backoff (seconds) for calls referencing a just-created app registration. Graph
     * replicas can lag a few seconds behind the create, which surfaces as a bogus
     * 403 "the backing application ... must in the local tenant" or a 404.
     */
    private static final long[] DEFAULT_RETRY_DELAYS_SECONDS = {1, 2, 3, 5, 8};

    private final ServiceIntegrationService integrations;
    private final AzureGraphClient graphClient;
    private final GoogleIamClient googleIamClient;
    private final AwsIamClient awsIamClient;
    private final GitLabClient gitLabClient;
    private final CloudflareClient cloudflareClient;
    private final ApiKeyService apiKeyService;
    private final long[] retryDelaysSeconds;

    @Autowired
    public KeyProvisioningService(ServiceIntegrationService integrations, AzureGraphClient graphClient,
            GoogleIamClient googleIamClient, AwsIamClient awsIamClient, GitLabClient gitLabClient,
            CloudflareClient cloudflareClient, ApiKeyService apiKeyService) {
        this(integrations, graphClient, googleIamClient, awsIamClient, gitLabClient, cloudflareClient,
                apiKeyService, DEFAULT_RETRY_DELAYS_SECONDS);
    }

    KeyProvisioningService(ServiceIntegrationService integrations, AzureGraphClient graphClient,
            GoogleIamClient googleIamClient, AwsIamClient awsIamClient, GitLabClient gitLabClient,
            CloudflareClient cloudflareClient, ApiKeyService apiKeyService, long[] retryDelaysSeconds) {
        this.integrations = integrations;
        this.graphClient = graphClient;
        this.googleIamClient = googleIamClient;
        this.awsIamClient = awsIamClient;
        this.gitLabClient = gitLabClient;
        this.cloudflareClient = cloudflareClient;
        this.apiKeyService = apiKeyService;
        this.retryDelaysSeconds = retryDelaysSeconds;
    }

    /**
     * Creates a new credential named {@code name} in the integration's provider and
     * stores the result locally. If a step after the initial create fails for good,
     * the partially created client is deleted again so nothing half-configured is
     * left behind in the provider.
     *
     * @param owner the person asking for the key; the stored key becomes theirs
     * @throws ProvisioningException if any provider call fails
     */
    public ApiKey provision(Long integrationId, String name, SecretValidity validity, String notes,
            UserAccount owner) {
        ServiceIntegration integration = integrations.getById(integrationId, owner);
        if (!integration.isEnabled()) {
            // checked here, not only in the pages that offer integrations, so a
            // direct POST cannot mint through a paused one either
            throw new ProvisioningException("The " + integration.getName()
                    + " integration has been switched off by a group admin. Nothing was created.");
        }
        return switch (integration.getProvider()) {
            case AZURE_AD -> provisionAzure(integration, name.trim(), validity, notes, owner);
            case GOOGLE_CLOUD -> provisionGoogle(integration, name.trim(), validity, notes, owner);
            case AWS_IAM -> provisionAws(integration, name.trim(), validity, notes, owner);
            case GITLAB -> provisionGitLab(integration, name.trim(), validity, notes, owner);
            case CLOUDFLARE -> provisionCloudflare(integration, name.trim(), validity, notes, owner);
        };
    }

    /**
     * Mints a fresh secret on the client behind {@code expiring} and stores it as a
     * new key for the same owner and group. Nothing about the client changes - no
     * new app registration, no new permissions - the existing one simply gains a
     * second secret, so both work until the old one expires on its own.
     * <p>
     * The new secret lives as long as the old one did (its original created-to-expiry
     * span, kept within Azure's limits), on the grounds that whoever set that span
     * chose it.
     *
     * @throws ProvisioningException if the provider refuses; the sweep tries again
     *                               on its next run
     */
    public ApiKey rotate(ApiKey expiring, ServiceIntegration integration) {
        if (!integration.getProvider().supportsRotation()) {
            throw new ProvisioningException(integration.getProvider().getLabel()
                    + " keys cannot be replaced automatically");
        }
        return switch (integration.getProvider()) {
            case AZURE_AD -> rotateAzure(expiring, integration);
            case GOOGLE_CLOUD -> rotateGoogle(expiring, integration);
            case AWS_IAM -> rotateAws(expiring, integration);
            case GITLAB -> rotateGitLab(expiring, integration);
            case CLOUDFLARE -> throw new ProvisioningException("Cloudflare keys cannot be replaced automatically");
        };
    }

    /** Azure: a second secret on the same app registration. Azure retires the old one itself. */
    private ApiKey rotateAzure(ApiKey expiring, ServiceIntegration integration) {
        String token = graphClient.acquireToken(integration.getTenantId(), integration.getClientId(),
                integration.getClientSecret());
        Instant requestedEnd = Instant.now().plus(replacementLifetimeDays(expiring), ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        CreatedSecret secret = withReplicationRetry("rotate client secret",
                () -> graphClient.addPassword(token, expiring.getProviderResourceId(), expiring.getName(),
                        requestedEnd));

        ApiKey replacement = apiKeyService.create(new ApiKeyDetails(expiring.getName(),
                expiring.getServiceName(), secret.secret(), LocalDate.now(), expiryDate(secret, requestedEnd),
                rotationNotes(expiring, null)), expiring.getOwner(), KeyOrigin.rotatedBy(integration.getName()),
                // the new secret's own id, so it can be removed in its turn
                KeyProvenance.rotatedFrom(expiring, secret.keyId()));
        integrations.markVerified(integration);
        log.info("Rotated {}: minted {} on the same client", expiring, replacement);
        return replacement;
    }

    /**
     * Google: a second JSON key on the same service account. Google would keep the
     * old key working for ever, so the replacement's expiry is the portal's own
     * date again, and the old key is deleted at Google by {@link #revoke} when its
     * date arrives. The service account itself - the identity the consumer is
     * granted roles as - is untouched.
     */
    private ApiKey rotateGoogle(ApiKey expiring, ServiceIntegration integration) {
        String projectId = integration.getTenantId();
        String serviceAccountEmail = expiring.getProviderResourceId();
        String token = googleIamClient.acquireToken(integration.getClientId(), integration.getClientSecret());
        String keyJson = googleIamClient.createServiceAccountKey(token, projectId, serviceAccountEmail);

        ApiKey replacement = apiKeyService.create(new ApiKeyDetails(expiring.getName(),
                expiring.getServiceName(), keyJson, LocalDate.now(),
                LocalDate.now().plusDays(replacementLifetimeDays(expiring)), rotationNotes(expiring, null)),
                expiring.getOwner(), KeyOrigin.rotatedBy(integration.getName()),
                KeyProvenance.rotatedFrom(expiring));
        integrations.markVerified(integration);
        log.info("Rotated {}: minted {} on service account {}", expiring, replacement, serviceAccountEmail);
        return replacement;
    }

    /**
     * AWS: a second access key on the same IAM user. IAM allows a user two keys,
     * which is exactly enough for a hand-over: the new pair is minted alongside
     * the old, and the old is deleted at AWS by {@link #revoke} when its date
     * arrives - freeing the slot for the rotation after this one.
     */
    private ApiKey rotateAws(ApiKey expiring, ServiceIntegration integration) {
        String userName = expiring.getProviderResourceId();
        CreatedAccessKey accessKey = awsIamClient.createAccessKey(integration.getClientId(),
                integration.getClientSecret(), userName);

        String value = "AWS_ACCESS_KEY_ID=" + accessKey.accessKeyId()
                + "\nAWS_SECRET_ACCESS_KEY=" + accessKey.secretAccessKey();
        ApiKey replacement = apiKeyService.create(new ApiKeyDetails(expiring.getName(),
                expiring.getServiceName(), value, LocalDate.now(),
                LocalDate.now().plusDays(replacementLifetimeDays(expiring)),
                rotationNotes(expiring, "Access key ID: " + accessKey.accessKeyId())),
                expiring.getOwner(), KeyOrigin.rotatedBy(integration.getName()),
                KeyProvenance.rotatedFrom(expiring));
        integrations.markVerified(integration);
        log.info("Rotated {}: minted {} on IAM user {}", expiring, replacement, userName);
        return replacement;
    }

    /**
     * Deletes an expired, already-replaced credential at its provider. For Google
     * and AWS, which would otherwise leave it working for ever, this is the step
     * that makes "expired" true: until it runs, the date on the key is a note.
     * For Azure, which has already stopped the secret on its end date, it takes
     * the dead entry off the app registration.
     * <p>
     * A credential the provider no longer has counts as done - somebody deleted
     * it in the console first, and the goal was that it be gone. Anything else
     * that goes wrong is thrown for the sweep to record and try again tomorrow.
     *
     * @throws ProvisioningException if the provider refuses, or the stored value
     *                               cannot tell the provider which credential to delete
     */
    public void revoke(ApiKey expired, ServiceIntegration integration) {
        if (!integration.getProvider().canDeleteReplaced()) {
            throw new ProvisioningException(integration.getProvider().getLabel()
                    + " leaves nothing behind to delete");
        }
        boolean deleted = switch (integration.getProvider()) {
            case AZURE_AD -> revokeAzure(expired, integration);
            case GOOGLE_CLOUD -> {
                String keyId = googleKeyIdOf(expired);
                String token = googleIamClient.acquireToken(integration.getClientId(),
                        integration.getClientSecret());
                yield googleIamClient.deleteServiceAccountKey(token, integration.getTenantId(),
                        expired.getProviderResourceId(), keyId);
            }
            case AWS_IAM -> awsIamClient.deleteAccessKey(integration.getClientId(), integration.getClientSecret(),
                    expired.getProviderResourceId(), awsAccessKeyIdOf(expired));
            default -> throw new IllegalStateException(integration.getProvider() + " cannot delete replaced keys");
        };
        apiKeyService.markRevokedAtProvider(expired.getId(), deleted
                ? "Expired; deleted at " + integration.getProvider().getLabel() + " by the portal, as its "
                        + "replacement had been minted"
                : "Expired; " + integration.getProvider().getLabel() + " no longer had this key, so there was "
                        + "nothing left to delete");
        integrations.markVerified(integration);
        log.info("Made the expiry of {} true at {}: {}", expired, integration, deleted ? "deleted" : "already gone");
    }

    /**
     * Azure: remove the expired secret from the app registration. By its keyId
     * where the portal recorded one; for secrets minted before it did, by name
     * among the registration's <em>expired</em> secrets - an expired secret is
     * inert whichever one it is, so that match is safe where a match among
     * live secrets would not be.
     */
    private boolean revokeAzure(ApiKey expired, ServiceIntegration integration) {
        String token = graphClient.acquireToken(integration.getTenantId(), integration.getClientId(),
                integration.getClientSecret());
        String application = expired.getProviderResourceId();
        if (expired.getProviderCredentialId() != null) {
            return graphClient.removePassword(token, application, expired.getProviderCredentialId());
        }
        boolean removed = false;
        for (String keyId : graphClient.expiredPasswordCredentials(token, application, expired.getName(),
                Instant.now())) {
            removed |= graphClient.removePassword(token, application, keyId);
        }
        return removed;
    }

    /** The Google key id, read out of the stored JSON key file. */
    private static String googleKeyIdOf(ApiKey key) {
        try {
            return ServiceAccountKey.privateKeyId(key.getKeyValue());
        } catch (IllegalArgumentException e) {
            throw new ProvisioningException("Could not work out which Google key to delete: " + e.getMessage(), e);
        }
    }

    /**
     * The access key id, read out of the stored two-line value. The id is the
     * public half of the pair and is in the notes too, but the value is the one
     * place it is guaranteed to be - the notes are the owner's to edit.
     */
    static String awsAccessKeyIdOf(ApiKey key) {
        String value = key.getKeyValue() == null ? "" : key.getKeyValue();
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("AWS_ACCESS_KEY_ID=")) {
                String id = trimmed.substring("AWS_ACCESS_KEY_ID=".length()).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
        }
        throw new ProvisioningException("Could not work out which AWS access key to delete: the stored value "
                + "has no AWS_ACCESS_KEY_ID line");
    }

    /**
     * GitLab: rotate the token in place.
     * <p>
     * GitLab's rotate endpoint issues a new value and revokes the old one in the
     * same call, so there is no hand-over to offer - the moment this returns,
     * the previous value is dead. The key row is therefore updated rather than
     * duplicated: same key, same name, same notes, new value and new expiry.
     * Creating a "replacement" row here would leave the old one sitting in the
     * list looking usable when it is not, which is the one outcome worth
     * designing against.
     */
    private ApiKey rotateGitLab(ApiKey expiring, ServiceIntegration integration) {
        LocalDate requested = LocalDate.now().plusDays(Math.min(replacementLifetimeDays(expiring), 365));
        GitLabClient.CreatedToken rotated = gitLabClient.rotateProjectAccessToken(integration.getTenantId(),
                integration.getClientSecret(), integration.getClientId(),
                expiring.getProviderResourceId(), requested);

        LocalDate expiresOn = rotated.expiresAt() != null ? LocalDate.parse(rotated.expiresAt()) : requested;
        ApiKey updated = apiKeyService.replaceValueInPlace(expiring.getId(), rotated.token(), expiresOn,
                String.valueOf(rotated.id()));
        log.info("Rotated {} in place through {} (token id {} -> {})", updated, integration,
                expiring.getProviderResourceId(), rotated.id());
        return updated;
    }

    /**
     * How long the replacement lives: as long as the old key did, held inside
     * Azure's two-year cap and a 30-day floor so a key with mangled dates still
     * gets a usable replacement.
     */
    static long replacementLifetimeDays(ApiKey expiring) {
        long days = ChronoUnit.DAYS.between(expiring.getCreatedOn(), expiring.getExpiresOn());
        return Math.min(730, Math.max(30, days));
    }

    /**
     * The old key's notes carry the client details; keep them, and say why this
     * key exists.
     *
     * @param extra a line about the new credential itself, where the old notes
     *              would otherwise describe the wrong one (AWS's access key id)
     */
    private static String rotationNotes(ApiKey expiring, String extra) {
        String notes = "Replaces \"" + expiring.getName() + "\", which expires on " + expiring.getExpiresOn()
                + ". Minted automatically.";
        if (extra != null) {
            notes = notes + "\n" + extra;
        }
        if (StringUtils.hasText(expiring.getNotes())) {
            notes = notes + "\n\n" + expiring.getNotes();
        }
        // the column holds 1000 characters; the first line is the one that matters
        return notes.length() <= 1000 ? notes : notes.substring(0, 1000);
    }

    /** Azure: app registration + service principal + client secret. */
    private ApiKey provisionAzure(ServiceIntegration integration, String displayName, SecretValidity validity,
            String notes, UserAccount owner) {
        String token = graphClient.acquireToken(integration.getTenantId(), integration.getClientId(),
                integration.getClientSecret());
        CreatedApplication application = graphClient.createApplication(token, displayName);

        CreatedSecret secret;
        Instant requestedEnd = Instant.now().plus(validity.getDays(), ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        try {
            withReplicationRetry("create service principal", () -> {
                graphClient.createServicePrincipal(token, application.clientId());
                return null;
            });
            secret = withReplicationRetry("create client secret",
                    () -> graphClient.addPassword(token, application.objectId(), displayName, requestedEnd));
        } catch (ProvisioningException e) {
            throw cleanUpAfterFailure(token, application, displayName, e);
        }

        ApiKey key = apiKeyService.create(new ApiKeyDetails(displayName, integration.getName(), secret.secret(),
                LocalDate.now(), expiryDate(secret, requestedEnd), buildNotes(notes, integration, application)),
                owner, KeyOrigin.provisionedBy(integration.getName()),
                // remembered so a replacement can be minted on this same client later,
                // and this secret removed from it once that replacement has taken over
                KeyProvenance.provisioned(integration.getId(), application.objectId(), secret.keyId()));
        integrations.markVerified(integration);
        log.info("Provisioned {} in {} (client id {})", key, integration, application.clientId());
        return key;
    }

    /**
     * Google Cloud: service account + JSON key. The key is the full credentials file
     * clients authenticate with, stored encrypted as the key value. Google service
     * account keys don't expire and the keys API can't be told to make them, so the
     * chosen validity is the portal's own date: it drives the reminders, and with
     * automatic replacement on it is when the portal deletes the key at Google
     * once a successor exists.
     */
    private ApiKey provisionGoogle(ServiceIntegration integration, String displayName, SecretValidity validity,
            String notes, UserAccount owner) {
        String projectId = integration.getTenantId();
        String token = googleIamClient.acquireToken(integration.getClientId(), integration.getClientSecret());
        CreatedServiceAccount account = googleIamClient.createServiceAccount(token, projectId,
                googleAccountId(displayName), displayName);

        String keyJson;
        try {
            // a freshly created account can take a moment to be visible to the keys API
            keyJson = withReplicationRetry("create service account key",
                    () -> googleIamClient.createServiceAccountKey(token, projectId, account.email()));
        } catch (ProvisioningException e) {
            throw cleanUpGoogleAfterFailure(token, projectId, account, displayName, e);
        }

        ApiKey key = apiKeyService.create(new ApiKeyDetails(displayName, integration.getName(), keyJson,
                LocalDate.now(), LocalDate.now().plusDays(validity.getDays()),
                buildGoogleNotes(notes, integration, account)),
                owner, KeyOrigin.provisionedBy(integration.getName()),
                // remembered so a replacement key can be minted on this same account later
                KeyProvenance.provisioned(integration.getId(), account.email()));
        integrations.markVerified(integration);
        log.info("Provisioned {} in {} (service account {})", key, integration, account.email());
        return key;
    }

    /**
     * GitLab: a project access token with the {@code api} scope, created in the
     * integration's project. One call, so there is nothing to clean up on failure.
     * GitLab requires an expiry and caps it at a year, so a longer choice is
     * quietly a year - and the date GitLab echoes back is the one stored.
     */
    private ApiKey provisionGitLab(ServiceIntegration integration, String displayName, SecretValidity validity,
            String notes, UserAccount owner) {
        LocalDate requested = LocalDate.now().plusDays(Math.min(validity.getDays(), 365));
        GitLabClient.CreatedToken token = gitLabClient.createProjectAccessToken(integration.getTenantId(),
                integration.getClientSecret(), integration.getClientId(), displayName, requested);

        LocalDate expiresOn = token.expiresAt() != null ? LocalDate.parse(token.expiresAt()) : requested;
        ApiKey key = apiKeyService.create(new ApiKeyDetails(displayName, integration.getName(), token.token(),
                LocalDate.now(), expiresOn, buildGitLabNotes(notes, integration, token)),
                owner, KeyOrigin.provisionedBy(integration.getName()),
                KeyProvenance.provisioned(integration.getId(), String.valueOf(token.id())));
        integrations.markVerified(integration);
        log.info("Provisioned {} in {} (token id {})", key, integration, token.id());
        return key;
    }

    private static String buildGitLabNotes(String userNotes, ServiceIntegration integration,
            GitLabClient.CreatedToken token) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Created via the ").append(integration.getName()).append(" integration.\n")
                .append("Project: ").append(integration.getClientId()).append('\n')
                .append("Token ID: ").append(token.id()).append('\n')
                .append("Scope: api, role: Maintainer. GitLab caps token lifetimes at one year.");
        return notes.toString();
    }

    /**
     * Cloudflare: a token carrying the template token's policies, with its own
     * name and expiry. The template is read fresh on every creation, so editing
     * its permissions in the dashboard changes what future keys get - no portal
     * configuration to keep in step.
     */
    private ApiKey provisionCloudflare(ServiceIntegration integration, String displayName,
            SecretValidity validity, String notes, UserAccount owner) {
        String adminToken = integration.getClientSecret();
        CloudflareClient.TemplateToken template = cloudflareClient.templateToken(adminToken,
                integration.getTenantId());
        Instant requestedEnd = Instant.now().plus(validity.getDays(), ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS);
        CloudflareClient.CreatedToken token = cloudflareClient.createToken(adminToken, displayName,
                template.policies(), requestedEnd);

        ApiKey key = apiKeyService.create(new ApiKeyDetails(displayName, integration.getName(), token.value(),
                LocalDate.now(), requestedEnd.atOffset(ZoneOffset.UTC).toLocalDate(),
                buildCloudflareNotes(notes, integration, template, token)),
                owner, KeyOrigin.provisionedBy(integration.getName()),
                KeyProvenance.provisioned(integration.getId(), token.id()));
        integrations.markVerified(integration);
        log.info("Provisioned {} in {} (token id {})", key, integration, token.id());
        return key;
    }

    private static String buildCloudflareNotes(String userNotes, ServiceIntegration integration,
            CloudflareClient.TemplateToken template, CloudflareClient.CreatedToken token) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Created via the ").append(integration.getName()).append(" integration.\n")
                .append("Token ID: ").append(token.id()).append('\n')
                .append("Permissions copied from the template token \"").append(template.name()).append("\".");
        return notes.toString();
    }

    /**
     * AWS: IAM user + access key. The stored value is the pair the consumer needs,
     * as two env-style lines ({@code AWS_ACCESS_KEY_ID=...} and
     * {@code AWS_SECRET_ACCESS_KEY=...}); the access key ID alone is not secret
     * and goes in the notes too, so the key is findable in the AWS console.
     * Access keys don't expire on their own, so the chosen validity is the portal's
     * own date: it drives the reminders, and with automatic replacement on it is
     * when the portal deletes the key at AWS once a successor exists.
     */
    private ApiKey provisionAws(ServiceIntegration integration, String displayName, SecretValidity validity,
            String notes, UserAccount owner) {
        String keyId = integration.getClientId();
        String secret = integration.getClientSecret();
        CreatedUser user = awsIamClient.createUser(keyId, secret, awsUserName(displayName));

        CreatedAccessKey accessKey;
        try {
            // a freshly created user can take a moment to be visible to other calls
            accessKey = withReplicationRetry("create access key",
                    () -> awsIamClient.createAccessKey(keyId, secret, user.userName()));
        } catch (ProvisioningException e) {
            throw cleanUpAwsAfterFailure(keyId, secret, user, displayName, e);
        }

        String value = "AWS_ACCESS_KEY_ID=" + accessKey.accessKeyId()
                + "\nAWS_SECRET_ACCESS_KEY=" + accessKey.secretAccessKey();
        ApiKey key = apiKeyService.create(new ApiKeyDetails(displayName, integration.getName(), value,
                LocalDate.now(), LocalDate.now().plusDays(validity.getDays()),
                buildAwsNotes(notes, integration, user, accessKey.accessKeyId())),
                owner, KeyOrigin.provisionedBy(integration.getName()),
                KeyProvenance.provisioned(integration.getId(), user.userName()));
        integrations.markVerified(integration);
        log.info("Provisioned {} in {} (IAM user {})", key, integration, user.userName());
        return key;
    }

    /**
     * Derives a valid IAM user name from the display name: up to 64 characters of
     * {@code [A-Za-z0-9+=,.@_-]}. A random suffix keeps repeated names from
     * colliding, since user names are unique per account.
     */
    static String awsUserName(String displayName) {
        String base = displayName.replaceAll("[^A-Za-z0-9+=,.@_-]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "key";
        }
        String suffix = "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
        int maxBase = 64 - suffix.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase).replaceAll("-+$", "");
        }
        return base + suffix;
    }

    /** Best-effort removal of the IAM user created earlier in a failed run. */
    private ProvisioningException cleanUpAwsAfterFailure(String keyId, String secret, CreatedUser user,
            String displayName, ProvisioningException failure) {
        try {
            awsIamClient.deleteUser(keyId, secret, user.userName());
            log.info("Cleaned up IAM user '{}' ({}) after failed provisioning", displayName, user.userName());
            return new ProvisioningException(failure.getMessage()
                    + " The partially created IAM user was removed from AWS again.", failure);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not clean up IAM user '{}' ({}) after failed provisioning: {}", displayName,
                    user.userName(), cleanupFailure.getMessage());
            return new ProvisioningException(failure.getMessage()
                    + " A partially created IAM user \"" + user.userName()
                    + "\" was left in AWS - delete it under IAM \u2192 Users.", failure);
        }
    }

    private static String buildAwsNotes(String userNotes, ServiceIntegration integration, CreatedUser user,
            String accessKeyId) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Created via the ").append(integration.getName()).append(" integration.\n")
                .append("IAM user: ").append(user.arn()).append('\n')
                .append("Access key ID: ").append(accessKeyId).append('\n')
                .append("The key value holds both halves of the pair. AWS access keys don't expire on their own: ")
                .append("the expiry here is the portal's, and with automatic replacement on the portal deletes ")
                .append("this key at AWS when the date arrives, once its replacement exists.");
        return notes.toString();
    }

    /**
     * Derives a valid service account ID from the display name: 6-30 chars of
     * {@code [a-z0-9-]} starting with a letter. A random suffix keeps repeated
     * names from colliding, since account IDs are unique per project.
     */
    static String googleAccountId(String displayName) {
        String base = displayName.toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "key-" + base;
        }
        String suffix = "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
        int maxBase = 30 - suffix.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase).replaceAll("-+$", "");
        }
        return base + suffix;
    }

    /** Best-effort removal of the service account created earlier in a failed run. */
    private ProvisioningException cleanUpGoogleAfterFailure(String token, String projectId,
            CreatedServiceAccount account, String displayName, ProvisioningException failure) {
        try {
            googleIamClient.deleteServiceAccount(token, projectId, account.email());
            log.info("Cleaned up service account '{}' ({}) after failed provisioning", displayName, account.email());
            return new ProvisioningException(failure.getMessage()
                    + " The partially created service account was removed from Google Cloud again.", failure);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not clean up service account '{}' ({}) after failed provisioning: {}", displayName,
                    account.email(), cleanupFailure.getMessage());
            return new ProvisioningException(failure.getMessage()
                    + " A partially created service account \"" + account.email()
                    + "\" was left in Google Cloud - delete it under IAM & Admin → Service accounts.", failure);
        }
    }

    private static String buildGoogleNotes(String userNotes, ServiceIntegration integration,
            CreatedServiceAccount account) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Created via the ").append(integration.getName()).append(" integration.\n")
                .append("Service account: ").append(account.email()).append('\n')
                .append("Project: ").append(integration.getTenantId()).append('\n')
                .append("The key value is the full JSON credentials file. Google service account keys don't expire ")
                .append("on their own: the expiry here is the portal's, and with automatic replacement on the portal ")
                .append("deletes this key at Google when the date arrives, once its replacement exists.");
        return notes.toString();
    }

    /**
     * Retries calls that reference a freshly created app registration. A 403 or 404
     * right after the create is almost always a replica that hasn't seen the new app
     * yet, not a real authorization problem; it resolves within seconds.
     */
    private <T> T withReplicationRetry(String action, Supplier<T> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (ProvisioningException e) {
                if (attempt >= retryDelaysSeconds.length || !looksLikeReplicationLag(e)) {
                    throw e;
                }
                log.info("Retrying '{}' after likely Azure replication lag (attempt {} of {}): {}",
                        action, attempt + 1, retryDelaysSeconds.length + 1, e.getMessage());
                sleepSeconds(retryDelaysSeconds[attempt]);
            }
        }
    }

    private static boolean looksLikeReplicationLag(ProvisioningException e) {
        return e.getCause() instanceof RestClientResponseException cause
                && (cause.getStatusCode().value() == 403 || cause.getStatusCode().value() == 404);
    }

    private static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProvisioningException("Interrupted while waiting to retry", e);
        }
    }

    /**
     * Best-effort removal of the app registration created earlier in a failed run,
     * so a failure doesn't strand a half-configured client in the tenant. The
     * returned exception tells the user whether that worked.
     */
    private ProvisioningException cleanUpAfterFailure(String token, CreatedApplication application,
            String displayName, ProvisioningException failure) {
        try {
            graphClient.deleteApplication(token, application.objectId());
            log.info("Cleaned up app registration '{}' ({}) after failed provisioning", displayName,
                    application.clientId());
            return new ProvisioningException(failure.getMessage()
                    + " The partially created app registration was removed from Azure again.", failure);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not clean up app registration '{}' ({}) after failed provisioning: {}", displayName,
                    application.clientId(), cleanupFailure.getMessage());
            return new ProvisioningException(failure.getMessage()
                    + " A partially created app registration named \"" + displayName + "\" (client ID "
                    + application.clientId() + ") was left in Azure - delete it under App registrations.", failure);
        }
    }

    private static LocalDate expiryDate(CreatedSecret secret, Instant requestedEnd) {
        if (secret.endDateTime() != null) {
            try {
                return OffsetDateTime.parse(secret.endDateTime()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            } catch (DateTimeParseException e) {
                // fall through to what we asked for
            }
        }
        return requestedEnd.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * The non-secret half of the credential goes into the notes so the key is usable
     * later: authenticating needs tenant ID, client ID and the (stored) secret.
     */
    private static String buildNotes(String userNotes, ServiceIntegration integration,
            CreatedApplication application) {
        StringBuilder notes = new StringBuilder();
        if (StringUtils.hasText(userNotes)) {
            notes.append(userNotes.trim()).append("\n\n");
        }
        notes.append("Created via the ").append(integration.getName()).append(" integration.\n")
                .append("Client ID: ").append(application.clientId()).append('\n')
                .append("Tenant ID: ").append(integration.getTenantId()).append('\n')
                .append("App registration object ID: ").append(application.objectId());
        return notes.toString();
    }
}
