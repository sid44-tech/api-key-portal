package com.sidbhat.apikeyportal.integration;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient;

@Service
public class ServiceIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(ServiceIntegrationService.class);

    private final ServiceIntegrationRepository repository;
    private final AzureGraphClient graphClient;
    private final GoogleIamClient googleIamClient;

    public ServiceIntegrationService(ServiceIntegrationRepository repository, AzureGraphClient graphClient,
            GoogleIamClient googleIamClient) {
        this.repository = repository;
        this.graphClient = graphClient;
        this.googleIamClient = googleIamClient;
    }

    public List<ServiceIntegration> findAll() {
        return repository.findAllByOrderByNameAsc();
    }

    public ServiceIntegration getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ServiceIntegrationNotFoundException(id));
    }

    /**
     * Verifies the credentials against the provider and saves the integration.
     * Saving never happens if verification fails, so a stored integration is
     * known to have worked at least once.
     *
     * @throws ProvisioningException if the provider rejects the credentials
     */
    public ServiceIntegration connect(ServiceProvider provider, String name, String tenantId, String clientId,
            String clientSecret) {
        ServiceIntegration integration = new ServiceIntegration(provider, name.trim(), tenantId.trim(),
                clientId.trim(), clientSecret.trim());
        verifyWithProvider(integration);
        integration.markVerified(Instant.now());
        ServiceIntegration saved = repository.save(integration);
        log.info("Connected {}", saved);
        return saved;
    }

    /**
     * Re-checks that the stored credentials still authenticate, and records when they last did.
     *
     * @throws ProvisioningException if the provider rejects the credentials
     */
    public ServiceIntegration test(Long id) {
        ServiceIntegration integration = getById(id);
        verifyWithProvider(integration);
        integration.markVerified(Instant.now());
        ServiceIntegration saved = repository.save(integration);
        log.info("Verified connection for {}", saved);
        return saved;
    }

    /** Called after another operation has already proven the credentials work. */
    public void markVerified(ServiceIntegration integration) {
        integration.markVerified(Instant.now());
        repository.save(integration);
    }

    public ServiceIntegration delete(Long id) {
        ServiceIntegration integration = getById(id);
        repository.delete(integration);
        log.info("Deleted {}", integration);
        return integration;
    }

    private void verifyWithProvider(ServiceIntegration integration) {
        // acquiring a token is the cheapest call that proves the stored credentials are right
        switch (integration.getProvider()) {
            case AZURE_AD -> graphClient.acquireToken(integration.getTenantId(), integration.getClientId(),
                    integration.getClientSecret());
            case GOOGLE_CLOUD -> googleIamClient.acquireToken(integration.getClientId(),
                    integration.getClientSecret());
        }
    }
}
