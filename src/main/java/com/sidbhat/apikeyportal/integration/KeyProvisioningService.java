package com.sidbhat.apikeyportal.integration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedApplication;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedSecret;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;
import com.sidbhat.apikeyportal.key.ApiKeyService;

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
    private final ApiKeyService apiKeyService;
    private final long[] retryDelaysSeconds;

    @Autowired
    public KeyProvisioningService(ServiceIntegrationService integrations, AzureGraphClient graphClient,
            ApiKeyService apiKeyService) {
        this(integrations, graphClient, apiKeyService, DEFAULT_RETRY_DELAYS_SECONDS);
    }

    KeyProvisioningService(ServiceIntegrationService integrations, AzureGraphClient graphClient,
            ApiKeyService apiKeyService, long[] retryDelaysSeconds) {
        this.integrations = integrations;
        this.graphClient = graphClient;
        this.apiKeyService = apiKeyService;
        this.retryDelaysSeconds = retryDelaysSeconds;
    }

    /**
     * Creates a new client (app registration) named {@code name} in the integration's
     * tenant, generates a client secret for it, and stores the result locally. If a
     * step after the app registration fails for good, the registration is deleted
     * again so nothing half-configured is left behind in the tenant.
     *
     * @throws ProvisioningException if any provider call fails
     */
    public ApiKey provision(Long integrationId, String name, SecretValidity validity, String notes) {
        ServiceIntegration integration = integrations.getById(integrationId);
        String displayName = name.trim();

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
                LocalDate.now(), expiryDate(secret, requestedEnd), buildNotes(notes, integration, application)));
        integrations.markVerified(integration);
        log.info("Provisioned {} in {} (client id {})", key, integration, application.clientId());
        return key;
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
