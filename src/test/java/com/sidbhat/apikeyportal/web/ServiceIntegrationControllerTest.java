package com.sidbhat.apikeyportal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationService;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.security.SecurityConfig;

@WebMvcTest(controllers = ServiceIntegrationController.class)
@Import(SecurityConfig.class)
class ServiceIntegrationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServiceIntegrationService integrationService;

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/integrations"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser
    void listsIntegrationsWithoutRenderingSecrets() throws Exception {
        when(integrationService.findAll()).thenReturn(List.of(new ServiceIntegration(ServiceProvider.AZURE_AD,
                "Azure AD", "tenant-1", "client-1", "fake-portal-secret")));

        mvc.perform(get("/integrations"))
                .andExpect(status().isOk())
                .andExpect(view().name("integrations/list"))
                .andExpect(content().string(Matchers.containsString("Azure AD")))
                .andExpect(content().string(Matchers.containsString("tenant-1")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("fake-portal-secret"))));
    }

    @Test
    @WithMockUser
    void showsEmptyStateWithSetupLink() throws Exception {
        when(integrationService.findAll()).thenReturn(List.of());

        mvc.perform(get("/integrations"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("No integrations yet")))
                .andExpect(content().string(Matchers.containsString("/integrations/new?provider=AZURE_AD")));
    }

    @Test
    @WithMockUser
    void setupPageShowsWalkthroughWithAzureLinks() throws Exception {
        mvc.perform(get("/integrations/new").param("provider", "AZURE_AD"))
                .andExpect(status().isOk())
                .andExpect(view().name("integrations/azure-setup"))
                .andExpect(content().string(Matchers.containsString("portal.azure.com")))
                .andExpect(content().string(Matchers.containsString("Application.ReadWrite.OwnedBy")))
                .andExpect(content().string(Matchers.containsString("Grant admin consent")));
    }

    @Test
    @WithMockUser
    void connectVerifiesAndSaves() throws Exception {
        when(integrationService.connect(eq(ServiceProvider.AZURE_AD), eq("Azure AD"), eq("tenant-1"),
                eq("client-1"), eq("portal-secret")))
                .thenReturn(new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD", "tenant-1", "client-1",
                        "portal-secret"));

        mvc.perform(post("/integrations/azure-ad").with(csrf())
                        .param("name", "Azure AD")
                        .param("tenantId", "tenant-1")
                        .param("clientId", "client-1")
                        .param("clientSecret", "portal-secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/integrations"))
                .andExpect(flash().attribute("success", Matchers.containsString("Connected Azure AD")));
    }

    @Test
    @WithMockUser
    void connectRedisplaysFormWhenInvalid() throws Exception {
        mvc.perform(post("/integrations/azure-ad").with(csrf())
                        .param("name", "Azure AD")
                        .param("tenantId", "")
                        .param("clientId", "client-1")
                        .param("clientSecret", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("integrations/azure-setup"))
                .andExpect(model().attributeHasFieldErrors("form", "tenantId", "clientSecret"));

        verify(integrationService, never()).connect(any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser
    void connectShowsProviderErrorWhenVerificationFails() throws Exception {
        when(integrationService.connect(any(), any(), any(), any(), any()))
                .thenThrow(new ProvisioningException("Azure AD rejected the credentials (HTTP 401): bad secret"));

        mvc.perform(post("/integrations/azure-ad").with(csrf())
                        .param("name", "Azure AD")
                        .param("tenantId", "tenant-1")
                        .param("clientId", "client-1")
                        .param("clientSecret", "wrong"))
                .andExpect(status().isOk())
                .andExpect(view().name("integrations/azure-setup"))
                .andExpect(model().attributeHasErrors("form"))
                .andExpect(content().string(Matchers.containsString("Azure AD rejected the credentials")));
    }

    @Test
    @WithMockUser
    void testEndpointReportsResult() throws Exception {
        when(integrationService.test(1L)).thenReturn(new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
                "tenant-1", "client-1", "portal-secret"));

        mvc.perform(post("/integrations/1/test").with(csrf()))
                .andExpect(redirectedUrl("/integrations"))
                .andExpect(flash().attribute("success", Matchers.containsString("works")));
    }

    @Test
    @WithMockUser
    void testEndpointReportsFailure() throws Exception {
        when(integrationService.test(1L)).thenThrow(new ProvisioningException("Azure AD rejected the credentials"));

        mvc.perform(post("/integrations/1/test").with(csrf()))
                .andExpect(redirectedUrl("/integrations"))
                .andExpect(flash().attribute("error", Matchers.containsString("rejected")));
    }

    @Test
    @WithMockUser
    void deleteRequiresCsrfToken() throws Exception {
        mvc.perform(post("/integrations/1/delete"))
                .andExpect(status().isForbidden());

        verify(integrationService, never()).delete(any());
    }

    @Test
    @WithMockUser
    void deleteRedirectsToList() throws Exception {
        when(integrationService.delete(1L)).thenReturn(new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
                "tenant-1", "client-1", "portal-secret"));

        mvc.perform(post("/integrations/1/delete").with(csrf()))
                .andExpect(redirectedUrl("/integrations"))
                .andExpect(flash().attribute("success", Matchers.containsString("Removed Azure AD")));
    }
}
