package com.sidbhat.apikeyportal.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientResponseException;

import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.activity.KeySource;
import com.sidbhat.apikeyportal.integration.aws.AwsIamClient;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.integration.cloudflare.CloudflareClient;
import com.sidbhat.apikeyportal.integration.gitlab.GitLabClient;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedApplication;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedSecret;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient;
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient.CreatedServiceAccount;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;
import com.sidbhat.apikeyportal.key.KeyProvenance;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;

@ExtendWith(MockitoExtension.class)
class KeyProvisioningServiceTest {

    @Mock
    private ServiceIntegrationService integrations;

    @Mock
    private AzureGraphClient graphClient;

    @Mock
    private GoogleIamClient googleIamClient;

    @Mock
    private AwsIamClient awsIamClient;

    @Mock
    private GitLabClient gitLabClient;

    @Mock
    private CloudflareClient cloudflareClient;

    @Mock
    private ApiKeyService apiKeyService;

    private KeyProvisioningService service;

    private final UserAccount owner = TestUsers.alex();

    private final ServiceIntegration integration = new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
            "tenant-1", "portal-client", "portal-secret");

    @BeforeEach
    void setUp() {
        // zero-length backoffs so retry paths run instantly in tests
        service = new KeyProvisioningService(integrations, graphClient, googleIamClient, awsIamClient,
                gitLabClient, cloudflareClient, apiKeyService,
                new long[] {0, 0});
    }

    // ---------- provisioning in AWS ----------

    private final ServiceIntegration aws = new ServiceIntegration(ServiceProvider.AWS_IAM, "AWS production",
            "123456789012", "AKIDEXAMPLE", "aws-secret");

    @Test
    void provisionsAnIamUserAndStoresBothHalvesOfTheAccessKey() {
        when(integrations.getById(9L, owner)).thenReturn(aws);
        when(awsIamClient.createUser(eq("AKIDEXAMPLE"), eq("aws-secret"), any()))
                .thenAnswer(inv -> new AwsIamClient.CreatedUser(inv.getArgument(2),
                        "arn:aws:iam::123456789012:user/keyportal/" + inv.getArgument(2)));
        when(awsIamClient.createAccessKey(eq("AKIDEXAMPLE"), eq("aws-secret"), any()))
                .thenReturn(new AwsIamClient.CreatedAccessKey("AKIANEWKEY", "new/secret+half"));
        when(apiKeyService.create(any(), eq(owner), any(), any()))
                .thenAnswer(inv -> new ApiKey("Reporting service", "AWS production",
                        ((ApiKeyDetails) inv.getArgument(0)).getKeyValue(), LocalDate.now(), null));

        service.provision(9L, " Reporting service ", SecretValidity.DAYS_180, null, owner);

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        ArgumentCaptor<KeyProvenance> provenance = ArgumentCaptor.forClass(KeyProvenance.class);
        verify(apiKeyService).create(details.capture(), eq(owner), any(), provenance.capture());

        // the consumer needs both halves; the pair is the stored (encrypted) value
        assertThat(details.getValue().getKeyValue())
                .contains("AWS_ACCESS_KEY_ID=AKIANEWKEY")
                .contains("AWS_SECRET_ACCESS_KEY=new/secret+half");
        // IAM access keys never expire on their own
        assertThat(details.getValue().getExpiresOn()).isNull();
        // the secret half stays out of the notes; the key id may be in them
        assertThat(details.getValue().getNotes()).contains("AKIANEWKEY").doesNotContain("new/secret+half");
        assertThat(provenance.getValue().integrationId()).isEqualTo(aws.getId());
        verify(integrations).markVerified(aws);
    }

    @Test
    void aFailedAccessKeyCreationRemovesTheHalfMadeUser() {
        when(integrations.getById(9L, owner)).thenReturn(aws);
        when(awsIamClient.createUser(any(), any(), any()))
                .thenAnswer(inv -> new AwsIamClient.CreatedUser(inv.getArgument(2), "arn:..."));
        when(awsIamClient.createAccessKey(any(), any(), any()))
                .thenThrow(new ProvisioningException("Could not create the access key (HTTP 403)"));

        assertThatThrownBy(() -> service.provision(9L, "Reporting", SecretValidity.DAYS_180, null, owner))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("was removed from AWS again");

        verify(awsIamClient).deleteUser(eq("AKIDEXAMPLE"), eq("aws-secret"), any());
        verify(apiKeyService, never()).create(any(), any(), any(), any());
    }

    @Test
    void iamUserNamesAreSanitisedAndUnique() {
        String name = KeyProvisioningService.awsUserName("Reporting service (EU/west)!");
        assertThat(name).matches("Reporting-service-EU-west-[0-9a-f]{4}");

        assertThat(KeyProvisioningService.awsUserName("ü")).matches("key-[0-9a-f]{4}");
        assertThat(KeyProvisioningService.awsUserName("x".repeat(100))).hasSizeLessThanOrEqualTo(64);
        assertThat(KeyProvisioningService.awsUserName("a")).isNotEqualTo(KeyProvisioningService.awsUserName("a"));
    }

    @Test
    void aSwitchedOffIntegrationRefusesBeforeAnyProviderCall() {
        integration.setEnabled(false);
        when(integrations.getById(7L, owner)).thenReturn(integration);

        assertThatThrownBy(() -> service.provision(7L, "Reporting", SecretValidity.DAYS_180, null, owner))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("switched off by a group admin");

        verifyNoInteractions(graphClient, googleIamClient, awsIamClient, gitLabClient, cloudflareClient);
        verify(apiKeyService, never()).create(any(), any(), any(), any());
    }

    // ---------- provisioning in GitLab and Cloudflare ----------

    @Test
    void gitLabTokensAreCappedAtAYearAndStoreWhatGitLabEchoesBack() {
        ServiceIntegration gitlab = new ServiceIntegration(ServiceProvider.GITLAB, "GitLab CI",
                "https://gitlab.example.com", "my-group/my-app", "glpat-admin");
        when(integrations.getById(11L, owner)).thenReturn(gitlab);
        when(gitLabClient.createProjectAccessToken(eq("https://gitlab.example.com"), eq("glpat-admin"),
                eq("my-group/my-app"), eq("CI token"), any(LocalDate.class)))
                .thenAnswer(inv -> new GitLabClient.CreatedToken(99L, "glpat-new",
                        inv.getArgument(4, LocalDate.class).toString()));
        when(apiKeyService.create(any(), eq(owner), any(), any()))
                .thenAnswer(inv -> new ApiKey("CI token", "GitLab CI", "glpat-new", LocalDate.now(), null));

        // two years chosen, but GitLab caps tokens at one year
        service.provision(11L, "CI token", SecretValidity.YEARS_2, null, owner);

        ArgumentCaptor<LocalDate> requested = ArgumentCaptor.forClass(LocalDate.class);
        verify(gitLabClient).createProjectAccessToken(any(), any(), any(), any(), requested.capture());
        assertThat(requested.getValue()).isEqualTo(LocalDate.now().plusDays(365));

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture(), eq(owner), any(), any());
        assertThat(details.getValue().getKeyValue()).isEqualTo("glpat-new");
        assertThat(details.getValue().getExpiresOn()).isEqualTo(LocalDate.now().plusDays(365));
    }

    @Test
    void cloudflareTokensCopyTheTemplatesPolicies() {
        ServiceIntegration cloudflare = new ServiceIntegration(ServiceProvider.CLOUDFLARE, "Cloudflare DNS",
                "tmpl-1", "tmpl-1", "cf-admin");
        List<Map<String, Object>> policies = List.of(Map.of("effect", "allow"));
        when(integrations.getById(12L, owner)).thenReturn(cloudflare);
        when(cloudflareClient.templateToken("cf-admin", "tmpl-1"))
                .thenReturn(new CloudflareClient.TemplateToken("portal-template", policies));
        when(cloudflareClient.createToken(eq("cf-admin"), eq("DNS updater"), eq(policies), any()))
                .thenReturn(new CloudflareClient.CreatedToken("new-1", "cf-new-secret"));
        when(apiKeyService.create(any(), eq(owner), any(), any()))
                .thenAnswer(inv -> new ApiKey("DNS updater", "Cloudflare DNS", "cf-new-secret",
                        LocalDate.now(), null));

        service.provision(12L, "DNS updater", SecretValidity.DAYS_90, null, owner);

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        ArgumentCaptor<KeyProvenance> provenance = ArgumentCaptor.forClass(KeyProvenance.class);
        verify(apiKeyService).create(details.capture(), eq(owner), any(), provenance.capture());
        assertThat(details.getValue().getKeyValue()).isEqualTo("cf-new-secret");
        assertThat(details.getValue().getExpiresOn())
                .isEqualTo(Instant.now().plus(90, java.time.temporal.ChronoUnit.DAYS)
                        .atOffset(java.time.ZoneOffset.UTC).toLocalDate());
        assertThat(details.getValue().getNotes()).contains("portal-template").doesNotContain("cf-new-secret");
        assertThat(provenance.getValue().providerResourceId()).isEqualTo("new-1");
    }

    // ---------- rotating an existing key ----------

    @Test
    void gitLabRotationReplacesTheKeyInPlaceRatherThanMintingASecond() {
        ServiceIntegration gitlab = new ServiceIntegration(ServiceProvider.GITLAB, "GitLab CI",
                "https://gitlab.example.com", "group/app", "glpat-admin");
        ApiKey existing = new ApiKey("CI token", "GitLab CI", "glpat-old",
                LocalDate.now().minusDays(360), LocalDate.now().plusDays(1));
        existing.assignTo(owner, owner.getGroup());
        existing.recordProvenance(7L, "99", true, null);
        ReflectionTestUtils.setField(existing, "id", 55L);

        when(gitLabClient.rotateProjectAccessToken(eq("https://gitlab.example.com"), eq("glpat-admin"),
                eq("group/app"), eq("99"), any(LocalDate.class)))
                .thenAnswer(inv -> new GitLabClient.CreatedToken(100L, "glpat-new",
                        inv.getArgument(4, LocalDate.class).toString()));
        when(apiKeyService.replaceValueInPlace(eq(55L), eq("glpat-new"), any(), eq("100")))
                .thenReturn(existing);

        service.rotate(existing, gitlab);

        // no second key row: the old token is already dead, so a "replacement"
        // sitting next to it would be a working-looking key that is not
        verify(apiKeyService, never()).create(any(), any(), any(), any());
        verify(apiKeyService).replaceValueInPlace(eq(55L), eq("glpat-new"), any(), eq("100"));
    }

    /** A provisioned key of Alex's, as the rotation sweep would hand it over. */
    private ApiKey expiringKey() {
        ApiKey key = new ApiKey("Reporting service", "Azure AD", "old-secret",
                LocalDate.now().minusDays(175), LocalDate.now().plusDays(5));
        key.assignTo(owner, owner.getGroup());
        key.setNotes("Client ID: new-client-id");
        key.recordProvenance(7L, "obj-1", null, null);
        ReflectionTestUtils.setField(key, "id", 42L);
        return key;
    }

    @Test
    void rotationMintsAFreshSecretOnTheSameClient() {
        ApiKey expiring = expiringKey();
        when(graphClient.acquireToken("tenant-1", "portal-client", "portal-secret")).thenReturn("tok");
        when(graphClient.addPassword(eq("tok"), eq("obj-1"), eq("Reporting service"), any(Instant.class)))
                .thenReturn(new CreatedSecret("fresh-secret", null));
        when(apiKeyService.create(any(), eq(owner), any(), any()))
                .thenAnswer(inv -> new ApiKey("Reporting service", "Azure AD", "fresh-secret",
                        LocalDate.now(), LocalDate.now().plusDays(180)));

        service.rotate(expiring, integration);

        // no new app registration, no new service principal: the same client
        // simply gains a second secret
        verify(graphClient, never()).createApplication(any(), any());
        verify(graphClient, never()).createServicePrincipal(any(), any());

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        ArgumentCaptor<KeyOrigin> origin = ArgumentCaptor.forClass(KeyOrigin.class);
        ArgumentCaptor<KeyProvenance> provenance = ArgumentCaptor.forClass(KeyProvenance.class);
        verify(apiKeyService).create(details.capture(), eq(owner), origin.capture(), provenance.capture());

        assertThat(details.getValue().getKeyValue()).isEqualTo("fresh-secret");
        assertThat(details.getValue().getName()).isEqualTo("Reporting service");
        assertThat(origin.getValue().source()).isEqualTo(KeySource.ROTATED);
        // the replacement stays tied to the same provider resource, points back at
        // the key it replaces, and lands in the old key's group - not wherever the
        // owner happens to be now
        assertThat(provenance.getValue().integrationId()).isEqualTo(7L);
        assertThat(provenance.getValue().providerResourceId()).isEqualTo("obj-1");
        assertThat(provenance.getValue().rotatedFromKeyId()).isEqualTo(42L);
        assertThat(provenance.getValue().group()).isEqualTo(expiring.getGroup());
    }

    @Test
    void theReplacementLivesAsLongAsTheOldKeyDid() {
        // created 1 Jan, expires 1 Jul: a 181-day key gets a 181-day replacement
        ApiKey key = new ApiKey("k", "s", "v", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1));
        assertThat(KeyProvisioningService.replacementLifetimeDays(key)).isEqualTo(181);

        // mangled or extreme spans are pulled back to something usable
        ApiKey tooShort = new ApiKey("k", "s", "v", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
        assertThat(KeyProvisioningService.replacementLifetimeDays(tooShort)).isEqualTo(30);
        ApiKey tooLong = new ApiKey("k", "s", "v", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1));
        assertThat(KeyProvisioningService.replacementLifetimeDays(tooLong)).isEqualTo(730);
    }

    @Test
    void rotationRefusesProvidersItCannotMintIn() {
        ServiceIntegration google = new ServiceIntegration(ServiceProvider.GOOGLE_CLOUD, "GCP",
                "project-1", "client", "secret");

        assertThatThrownBy(() -> service.rotate(expiringKey(), google))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("cannot be replaced automatically");
        verifyNoInteractions(graphClient);
    }

    /** The bogus 403 Graph returns when a replica hasn't seen the new app registration yet. */
    private static ProvisioningException replicationLag() {
        return new ProvisioningException(
                "Could not create the service principal (HTTP 403): the backing application ... must in the local tenant",
                new RestClientResponseException("403", HttpStatusCode.valueOf(403), "Forbidden", null, null, null));
    }

    @Test
    void provisionsClientAndStoresSecretAsKey() {
        when(integrations.getById(7L, owner)).thenReturn(integration);
        when(graphClient.acquireToken("tenant-1", "portal-client", "portal-secret")).thenReturn("tok");
        when(graphClient.createApplication("tok", "Reporting service"))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        when(graphClient.addPassword(eq("tok"), eq("obj-1"), eq("Reporting service"), any(Instant.class)))
                .thenReturn(new CreatedSecret("generated-secret", "2027-04-27T12:00:00Z"));
        when(apiKeyService.create(any(), eq(owner), any(), any())).thenAnswer(inv -> new ApiKey("Reporting service", "Azure AD",
                "generated-secret", LocalDate.now(), LocalDate.of(2027, 4, 27)));

        service.provision(7L, "  Reporting service ", SecretValidity.DAYS_180, "for the dashboards", owner);

        verify(graphClient).createServicePrincipal("tok", "new-client-id");
        verify(integrations).markVerified(integration);

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        ArgumentCaptor<KeyOrigin> origin = ArgumentCaptor.forClass(KeyOrigin.class);
        verify(apiKeyService).create(details.capture(), eq(owner), origin.capture(), any());
        assertThat(details.getValue().getName()).isEqualTo("Reporting service");
        assertThat(details.getValue().getServiceName()).isEqualTo("Azure AD");
        assertThat(details.getValue().getKeyValue()).isEqualTo("generated-secret");
        assertThat(details.getValue().getCreatedOn()).isEqualTo(LocalDate.now());
        assertThat(details.getValue().getExpiresOn()).isEqualTo(LocalDate.of(2027, 4, 27));
        // the history has to say this key was minted through the shared integration
        assertThat(origin.getValue()).isEqualTo(KeyOrigin.provisionedBy("Azure AD"));
        assertThat(details.getValue().getNotes())
                .contains("for the dashboards")
                .contains("Client ID: new-client-id")
                .contains("Tenant ID: tenant-1")
                .contains("object ID: obj-1");
    }

    @Test
    void fallsBackToRequestedExpiryWhenResponseOmitsIt() {
        when(integrations.getById(7L, owner)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        when(graphClient.addPassword(anyString(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(new CreatedSecret("generated-secret", null));
        when(apiKeyService.create(any(), eq(owner), any(), any())).thenAnswer(inv -> new ApiKey("n", "s", "v", LocalDate.now(), null));

        service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null, owner);

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        ArgumentCaptor<KeyOrigin> origin = ArgumentCaptor.forClass(KeyOrigin.class);
        verify(apiKeyService).create(details.capture(), eq(owner), origin.capture(), any());
        assertThat(details.getValue().getExpiresOn())
                .isBetween(LocalDate.now().plusDays(89), LocalDate.now().plusDays(91));
    }

    @Test
    void retriesServicePrincipalCreationAfterReplicationLag() {
        when(integrations.getById(7L, owner)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(replicationLag()).doThrow(replicationLag()).doNothing()
                .when(graphClient).createServicePrincipal("tok", "new-client-id");
        when(graphClient.addPassword(anyString(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(new CreatedSecret("generated-secret", "2027-04-27T12:00:00Z"));
        when(apiKeyService.create(any(), eq(owner), any(), any())).thenAnswer(inv -> new ApiKey("n", "s", "v", LocalDate.now(), null));

        service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null, owner);

        verify(graphClient, times(3)).createServicePrincipal("tok", "new-client-id");
        verify(apiKeyService).create(any(), eq(owner), any(), any());
        verify(graphClient, never()).deleteApplication(anyString(), anyString());
    }

    @Test
    void cleansUpAppRegistrationWhenRetriesAreExhausted() {
        when(integrations.getById(7L, owner)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(replicationLag()).when(graphClient).createServicePrincipal("tok", "new-client-id");

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null, owner))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("service principal")
                .hasMessageContaining("removed from Azure");

        // two configured retries = three attempts in total
        verify(graphClient, times(3)).createServicePrincipal("tok", "new-client-id");
        verify(graphClient).deleteApplication("tok", "obj-1");
        verify(apiKeyService, never()).create(any(), any(), any(), any());
    }

    @Test
    void doesNotRetryNonReplicationErrorsButStillCleansUp() {
        when(integrations.getById(7L, owner)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(new ProvisioningException("Could not create the service principal (HTTP 400): bad request",
                new RestClientResponseException("400", HttpStatusCode.valueOf(400), "Bad Request", null, null, null)))
                .when(graphClient).createServicePrincipal("tok", "new-client-id");

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null, owner))
                .isInstanceOf(ProvisioningException.class);

        verify(graphClient, times(1)).createServicePrincipal("tok", "new-client-id");
        verify(graphClient).deleteApplication("tok", "obj-1");
        verify(apiKeyService, never()).create(any(), any(), any(), any());
    }

    @Test
    void reportsLeftoverWhenCleanupAlsoFails() {
        when(integrations.getById(7L, owner)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        doThrow(replicationLag()).when(graphClient).createServicePrincipal("tok", "new-client-id");
        doThrow(new ProvisioningException("Could not delete the app registration (HTTP 403)"))
                .when(graphClient).deleteApplication("tok", "obj-1");

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null, owner))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("left in Azure")
                .hasMessageContaining("new-client-id");

        verify(apiKeyService, never()).create(any(), any(), any(), any());
    }

    private final ServiceIntegration googleIntegration = new ServiceIntegration(ServiceProvider.GOOGLE_CLOUD,
            "Google Cloud", "my-project", "portal@my-project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----");

    @Test
    void provisionsGoogleServiceAccountAndStoresKeyJson() {
        when(integrations.getById(9L, owner)).thenReturn(googleIntegration);
        when(googleIamClient.acquireToken("portal@my-project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----"))
                .thenReturn("gtok");
        when(googleIamClient.createServiceAccount(eq("gtok"), eq("my-project"), anyString(), eq("Reporting service")))
                .thenReturn(new CreatedServiceAccount("reporting@my-project.iam.gserviceaccount.com", "123"));
        when(googleIamClient.createServiceAccountKey("gtok", "my-project",
                "reporting@my-project.iam.gserviceaccount.com")).thenReturn("{\"type\":\"service_account\"}");
        when(apiKeyService.create(any(), eq(owner), any(), any())).thenAnswer(inv -> new ApiKey("Reporting service", "Google Cloud",
                "{\"type\":\"service_account\"}", LocalDate.now(), null));

        service.provision(9L, " Reporting service ", SecretValidity.DAYS_180, "for the dashboards", owner);

        verify(integrations).markVerified(googleIntegration);
        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        ArgumentCaptor<KeyOrigin> origin = ArgumentCaptor.forClass(KeyOrigin.class);
        verify(apiKeyService).create(details.capture(), eq(owner), origin.capture(), any());
        assertThat(details.getValue().getName()).isEqualTo("Reporting service");
        assertThat(details.getValue().getServiceName()).isEqualTo("Google Cloud");
        assertThat(origin.getValue()).isEqualTo(KeyOrigin.provisionedBy("Google Cloud"));
        assertThat(details.getValue().getKeyValue()).isEqualTo("{\"type\":\"service_account\"}");
        assertThat(details.getValue().getExpiresOn()).isNull();
        assertThat(details.getValue().getNotes())
                .contains("for the dashboards")
                .contains("reporting@my-project.iam.gserviceaccount.com")
                .contains("Project: my-project");
    }

    @Test
    void cleansUpGoogleServiceAccountWhenKeyCreationFails() {
        when(integrations.getById(9L, owner)).thenReturn(googleIntegration);
        when(googleIamClient.acquireToken(anyString(), anyString())).thenReturn("gtok");
        when(googleIamClient.createServiceAccount(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CreatedServiceAccount("reporting@my-project.iam.gserviceaccount.com", "123"));
        when(googleIamClient.createServiceAccountKey(anyString(), anyString(), anyString()))
                .thenThrow(new ProvisioningException("Could not create the service account key (HTTP 400)",
                        new RestClientResponseException("400", HttpStatusCode.valueOf(400), "Bad Request",
                                null, null, null)));

        assertThatThrownBy(() -> service.provision(9L, "Reporting service", SecretValidity.DAYS_90, null, owner))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("removed from Google Cloud");

        verify(googleIamClient).deleteServiceAccount("gtok", "my-project",
                "reporting@my-project.iam.gserviceaccount.com");
        verify(apiKeyService, never()).create(any(), any(), any(), any());
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
        when(integrations.getById(7L, owner)).thenReturn(integration);
        when(graphClient.acquireToken(anyString(), anyString(), anyString())).thenReturn("tok");
        when(graphClient.createApplication(anyString(), anyString()))
                .thenThrow(new ProvisioningException("Could not create the app registration (HTTP 403)"));

        assertThatThrownBy(() -> service.provision(7L, "Reporting service", SecretValidity.DAYS_90, null, owner))
                .isInstanceOf(ProvisioningException.class);

        verify(apiKeyService, never()).create(any(), any(), any(), any());
    }
}
