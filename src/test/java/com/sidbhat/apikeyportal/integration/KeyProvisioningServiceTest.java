package com.sidbhat.apikeyportal.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedApplication;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedSecret;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient.CreatedServiceAccount;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;
import com.sidbhat.apikeyportal.key.ApiKeyService;

@ExtendWith(MockitoExtension.class)
class KeyProvisioningServiceTest {

    @Mock
    private ServiceIntegrationService integrations;

    @Mock
    private AzureGraphClient graphClient;

    @Mock
    private GoogleIamClient googleIamClient;

    @Mock
    private ApiKeyService apiKeyService;

    private KeyProvisioningService service;

    private final ServiceIntegration integration = new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
            "tenant-1", "portal-client", "portal-secret");

    @BeforeEach
    void setUp() {
        // zero-length backoffs so retry paths run instantly in tests
        service = new KeyProvisioningService(integrations, graphClient, googleIamClient, apiKeyService,
                new long[] {0, 0});
    }

    /** The bogus 403 Graph returns when a replica hasn't seen the new app registration yet. */
    private static ProvisioningException replicationLag() {
        return new ProvisioningException(
                "Could not create the service principal (HTTP 403): the backing application ... must in the local tenant",
                new RestClientResponseException("403", HttpStatusCode.valueOf(403), "Forbidden", null, null, null));
    }

    @Test
    void provisionsClientAndStoresSecretAsKey() {
        when(integrations.getById(7L)).thenReturn(integration);
        when(graphClient.acquireToken("tenant-1", "portal-client", "portal-secret")).thenReturn("tok");
        when(graphClient.createApplication("tok", "Reporting service"))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        when(graphClient.addPassword(eq("tok"), eq("obj-1"), eq("Reporting service"), any(Instant.class)))
                .thenReturn(new CreatedSecret("generated-secret", "2027-04-27T12:00:00Z"));
        when(apiKeyService.create(any())).thenAnswer(inv -> new ApiKey("Reporting service", "Azure AD",
                "generated-secret", LocalDate.now(), LocalDate.of(2027, 4, 27)));

        service.provision(7L, "  Reporting service ", SecretValidity.DAYS_180, "for the dashboards");

        verify(graphClient).createServicePrincipal("tok", "new-client-id");
        verify(integrations).markVerified(integration);

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture());
        assertThat(details.getValue().getName()).isEqualTo("Reporting service");
        assertThat(details.getValue().getServiceName()).isEqualTo("Azure AD");
        assertThat(details.getValue().getKeyValue()).isEqualTo("generated-secret");
        assertThat(details.getValue().getCreatedOn()).isEqualTo(LocalDate.now());
        assertThat(details.getValue().getExpiresOn()).isEqualTo(LocalDate.of(2027, 4, 27));
        assertThat(details.getValue().getNotes())
                .contains("for the dashboards")
                .contains("Client ID: new-client-id")
                .contains("Tenant ID: tenant-1")
                .contains("object ID: obj-1");
    }

    @Test
    void fallsBackToRequestedExpiryWhenResponseOmitsIt() {
        when(integrations.getById(7L)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        when(graphClient.addPassword(anyString(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(new CreatedSecret("generated-secret", null));
        when(apiKeyService.create(any())).thenAnswer(inv -> new ApiKey("n", "s", "v", LocalDate.now(), null));

        service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null);

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture());
        assertThat(details.getValue().getExpiresOn())
                .isBetween(LocalDate.now().plusDays(89), LocalDate.now().plusDays(91));
    }

    @Test
    void retriesServicePrincipalCreationAfterReplicationLag() {
        when(integrations.getById(7L)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(replicationLag()).doThrow(replicationLag()).doNothing()
                .when(graphClient).createServicePrincipal("tok", "new-client-id");
        when(graphClient.addPassword(anyString(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(new CreatedSecret("generated-secret", "2027-04-27T12:00:00Z"));
        when(apiKeyService.create(any())).thenAnswer(inv -> new ApiKey("n", "s", "v", LocalDate.now(), null));

        service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null);

        verify(graphClient, times(3)).createServicePrincipal("tok", "new-client-id");
        verify(apiKeyService).create(any());
        verify(graphClient, never()).deleteApplication(anyString(), anyString());
    }

    @Test
    void cleansUpAppRegistrationWhenRetriesAreExhausted() {
        when(integrations.getById(7L)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(replicationLag()).when(graphClient).createServicePrincipal("tok", "new-client-id");

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("service principal")
                .hasMessageContaining("removed from Azure");

        // two configured retries = three attempts in total
        verify(graphClient, times(3)).createServicePrincipal("tok", "new-client-id");
        verify(graphClient).deleteApplication("tok", "obj-1");
        verify(apiKeyService, never()).create(any());
    }

    @Test
    void doesNotRetryNonReplicationErrorsButStillCleansUp() {
        when(integrations.getById(7L)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(new ProvisioningException("Could not create the service principal (HTTP 400): bad request",
                new RestClientResponseException("400", HttpStatusCode.valueOf(400), "Bad Request", null, null, null)))
                .when(graphClient).createServicePrincipal("tok", "new-client-id");

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null))
                .isInstanceOf(ProvisioningException.class);

        verify(graphClient, times(1)).createServicePrincipal("tok", "new-client-id");
        verify(graphClient).deleteApplication("tok", "obj-1");
        verify(apiKeyService, never()).create(any());
    }

    @Test
    void reportsLeftoverWhenCleanupAlsoFails() {
        when(integrations.getById(7L)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(replicationLag()).when(graphClient).createServicePrincipal("tok", "new-client-id");
        doThrow(new ProvisioningException("Could not delete the app registration (HTTP 403)"))
                .when(graphClient).deleteApplication("tok", "obj-1");

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("left in Azure")
                .hasMessageContaining("new-client-id");

        verify(apiKeyService, never()).create(any());
    }

    private final ServiceIntegration googleIntegration = new ServiceIntegration(ServiceProvider.GOOGLE_CLOUD,
            "Google Cloud", "my-project", "portal@my-project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----");

    @Test
    void provisionsGoogleServiceAccountAndStoresKeyJson() {
        when(integrations.getById(9L)).thenReturn(googleIntegration);
        when(googleIamClient.acquireToken("portal@my-project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----"))
                .thenReturn("gtok");
        when(googleIamClient.createServiceAccount(eq("gtok"), eq("my-project"), anyString(), eq("Reporting service")))
                .thenReturn(new CreatedServiceAccount("reporting@my-project.iam.gserviceaccount.com", "123"));
        when(googleIamClient.createServiceAccountKey("gtok", "my-project",
                "reporting@my-project.iam.gserviceaccount.com")).thenReturn("{\"type\":\"service_account\"}");
        when(apiKeyService.create(any())).thenAnswer(inv -> new ApiKey("Reporting service", "Google Cloud",
                "{\"type\":\"service_account\"}", LocalDate.now(), null));

        service.provision(9L, " Reporting service ", SecretValidity.DAYS_180, "for the dashboards");

        verify(integrations).markVerified(googleIntegration);
        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture());
        assertThat(details.getValue().getName()).isEqualTo("Reporting service");
        assertThat(details.getValue().getServiceName()).isEqualTo("Google Cloud");
        assertThat(details.getValue().getKeyValue()).isEqualTo("{\"type\":\"service_account\"}");
        assertThat(details.getValue().getExpiresOn()).isNull();
        assertThat(details.getValue().getNotes())
                .contains("for the dashboards")
                .contains("reporting@my-project.iam.gserviceaccount.com")
                .contains("Project: my-project");
    }

    @Test
    void cleansUpGoogleServiceAccountWhenKeyCreationFails() {
        when(integrations.getById(9L)).thenReturn(googleIntegration);
        when(googleIamClient.acquireToken(anyString(), anyString())).thenReturn("gtok");
        when(googleIamClient.createServiceAccount(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CreatedServiceAccount("reporting@my-project.iam.gserviceaccount.com", "123"));
        when(googleIamClient.createServiceAccountKey(anyString(), anyString(), anyString()))
                .thenThrow(new ProvisioningException("Could not create the service account key (HTTP 400)",
                        new RestClientResponseException("400", HttpStatusCode.valueOf(400), "Bad Request",
                                null, null, null)));

        assertThatThrownBy(() -> service.provision(9L, "Reporting service", SecretValidity.DAYS_90, null))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("removed from Google Cloud");

        verify(googleIamClient).deleteServiceAccount("gtok", "my-project",
                "reporting@my-project.iam.gserviceaccount.com");
        verify(apiKeyService, never()).create(any());
    }

    @Test
    void derivesValidGoogleAccountIds() {
        assertThat(KeyProvisioningService.googleAccountId("Reporting Service!"))
                .matches("[a-z][a-z0-9-]{5,29}")
                .startsWith("reporting-service");
        assertThat(KeyProvisioningService.googleAccountId("42 crunchers"))
                .matches("[a-z][a-z0-9-]{5,29}");
        assertThat(KeyProvisioningService.googleAccountId("x"))
                .matches("[a-z][a-z0-9-]{5,29}");
        assertThat(KeyProvisioningService.googleAccountId(
                "a very long display name that exceeds the thirty character limit"))
                .matches("[a-z][a-z0-9-]{5,29}");
    }

    @Test
    void doesNotStoreAnythingWhenProviderCallFails() {
        when(integrations.getById(7L)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenThrow(new ProvisioningException("Could not create the app registration (HTTP 403)"));

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null))
                .isInstanceOf(ProvisioningException.class);

        verify(apiKeyService, never()).create(any());
    }
}
