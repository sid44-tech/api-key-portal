package com.sidbhat.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationRepository;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedApplication;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient.CreatedSecret;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;

/**
 * End-to-end through the web layer against the real database and encryption, with
 * only the outbound Azure calls stubbed: connect an integration, provision a key
 * through it, and reveal the captured secret.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationProvisioningIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ServiceIntegrationRepository integrationRepository;

    @Autowired
    private ApiKeyRepository keyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AzureGraphClient graphClient;

    @AfterEach
    void cleanUp() {
        keyRepository.deleteAll();
        integrationRepository.deleteAll();
    }

    @Test
    @WithMockUser
    void connectsIntegrationVerifyingCredentialsAndStoresSecretEncrypted() throws Exception {
        when(graphClient.acquireToken("tenant-1", "portal-client", "fake-portal-secret")).thenReturn("tok");

        mvc.perform(post("/integrations/azure-ad").with(csrf())
                        .param("name", "Azure AD")
                        .param("tenantId", "tenant-1")
                        .param("clientId", "portal-client")
                        .param("clientSecret", "fake-portal-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/integrations"));

        ServiceIntegration integration = integrationRepository.findAll().get(0);
        assertThat(integration.getProvider()).isEqualTo(ServiceProvider.AZURE_AD);
        assertThat(integration.getLastVerifiedAt()).isNotNull();

        String raw = jdbcTemplate.queryForObject("select client_secret from service_integration where id = ?",
                String.class, integration.getId());
        assertThat(raw).doesNotContain("fake-portal-secret");
    }

    @Test
    @WithMockUser
    void provisionsKeyThroughIntegrationAndCapturesSecret() throws Exception {
        ServiceIntegration integration = integrationRepository.save(new ServiceIntegration(ServiceProvider.AZURE_AD,
                "Azure AD", "tenant-1", "portal-client", "fake-portal-secret"));
        when(graphClient.acquireToken("tenant-1", "portal-client", "fake-portal-secret")).thenReturn("tok");
        when(graphClient.createApplication("tok", "Reporting service"))
                .thenReturn(new CreatedApplication("obj-1", "new-client-id"));
        when(graphClient.addPassword(eq("tok"), eq("obj-1"), anyString(), any(Instant.class)))
                .thenReturn(new CreatedSecret("generated-azure-secret", "2027-04-27T12:00:00Z"));

        // the add-key page offers the integration
        mvc.perform(get("/keys/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Create it in a connected service")));

        // the provisioning form shows the tenant
        mvc.perform(get("/keys/provision").param("integration", integration.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tenant-1")));

        mvc.perform(post("/keys/provision").with(csrf())
                        .param("integrationId", integration.getId().toString())
                        .param("name", "Reporting service")
                        .param("validity", "DAYS_180")
                        .param("notes", "for the dashboards"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/keys/*"));

        ApiKey key = keyRepository.findAll().get(0);
        assertThat(key.getName()).isEqualTo("Reporting service");
        assertThat(key.getServiceName()).isEqualTo("Azure AD");
        assertThat(key.getExpiresOn()).isEqualTo("2027-04-27");
        assertThat(key.getNotes()).contains("Client ID: new-client-id").contains("for the dashboards");

        // secret is encrypted at rest but revealable through the value endpoint
        String raw = jdbcTemplate.queryForObject("select key_value from api_key where id = ?", String.class,
                key.getId());
        assertThat(raw).doesNotContain("generated-azure-secret");
        mvc.perform(get("/keys/{id}/value", key.getId()))
                .andExpect(jsonPath("$.value").value("generated-azure-secret"));

        // the key detail page never renders the secret
        mvc.perform(get("/keys/{id}", key.getId()))
                .andExpect(content().string(not(containsString("generated-azure-secret"))));

        // provisioning success also counts as verification
        assertThat(integrationRepository.findById(integration.getId()).orElseThrow().getLastVerifiedAt()).isNotNull();
    }
}
