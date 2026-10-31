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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationService;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = ServiceIntegrationController.class)
@Import(SecurityConfig.class)
class ServiceIntegrationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServiceIntegrationService integrationService;

    @MockitoBean
    private UserAccountService userService;

    private final UserAccount alex = TestUsers.alex();

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/integrations"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser
    void listsIntegrationsWithoutRenderingSecrets() throws Exception {
        when(integrationService.findAll(alex)).thenReturn(List.of(new ServiceIntegration(ServiceProvider.AZURE_AD,
                "Azure AD", "tenant-1", "client-1", "fake-portal-secret")));

        mvc.perform(get("/integrations"))
                .andExpect(status().isOk())
                .andExpect(view().name("integrations/list"))
                .andExpect(content().string(Matchers.containsString("Azure AD")))
                .andExpect(content().string(Matchers.containsString("tenant-1")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("fake-portal-secret"))));
    }

    @Test
    @WithPortalUser
    void showsEmptyStateWithSetupLink() throws Exception {
        when(integrationService.findAll(alex)).thenReturn(List.of());

        mvc.perform(get("/integrations"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Create a new integration")))
                .andExpect(content().string(Matchers.containsString("/integrations/new?provider=AZURE_AD")))
                .andExpect(content().string(Matchers.containsString("/integrations/new?provider=GOOGLE_CLOUD")))
                .andExpect(content().string(Matchers.containsString("/keys/guided/github")))
                .andExpect(content().string(Matchers.containsString("/keys/guided/anthropic")));
    }

    @Test
    @WithPortalUser
    void setupPageShowsWalkthroughWithAzureLinks() throws Exception {
        mvc.perform(get("/integrations/new").param("provider", "AZURE_AD"))
                .andExpect(status().isOk())
                .andExpect(view().name("integrations/azure-setup"))
                .andExpect(content().string(Matchers.containsString("portal.azure.com")))
                .andExpect(content().string(Matchers.containsString("Application.ReadWrite.OwnedBy")))
                .andExpect(content().string(Matchers.containsString("Grant admin consent")));
    }

    @Test
    @WithPortalUser
    void connectVerifiesAndSaves() throws Exception {
        when(integrationService.connect(eq(ServiceProvider.AZURE_AD), eq("Azure AD"), eq("tenant-1"),
                eq("client-1"), eq("portal-secret"), eq(alex)))
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
    @WithPortalUser
    void connectRedisplaysFormWhenInvalid() throws Exception {
        mvc.perform(post("/integrations/azure-ad").with(csrf())
                        .param("name", "Azure AD")
                        .param("tenantId", "")
                        .param("clientId", "client-1")
                        .param("clientSecret", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("integrations/azure-setup"))
                .andExpect(model().attributeHasFieldErrors("form", "tenantId", "clientSecret"));

        verify(integrationService, never()).connect(any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithPortalUser
    void connectShowsProviderErrorWhenVerificationFails() throws Exception {
        when(integrationService.connect(any(), any(), any(), any(), any(), any()))
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
    @WithPortalUser
    void testEndpointReportsResult() throws Exception {
        when(integrationService.test(1L, alex)).thenReturn(new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
                "tenant-1", "client-1", "portal-secret"));

        mvc.perform(post("/integrations/1/test").with(csrf()))
                .andExpect(redirectedUrl("/integrations"))
                .andExpect(flash().attribute("success", Matchers.containsString("works")));
    }

    @Test
    @WithPortalUser
    void testEndpointReportsFailure() throws Exception {
        when(integrationService.test(1L, alex)).thenThrow(new ProvisioningException("Azure AD rejected the credentials"));

        mvc.perform(post("/integrations/1/test").with(csrf()))
                .andExpect(redirectedUrl("/integrations"))
                .andExpect(flash().attribute("error", Matchers.containsString("rejected")));
    }

    @Test
    @WithPortalUser
    void deleteRequiresCsrfToken() throws Exception {
        mvc.perform(post("/integrations/1/delete"))
                .andExpect(status().isForbidden());

        verify(integrationService, never()).delete(any(), any());
    }

    @Test
    @WithPortalUser
    void deleteRedirectsToList() throws Exception {
        when(integrationService.delete(1L, alex)).thenReturn(new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
                "tenant-1", "client-1", "portal-secret"));

        mvc.perform(post("/integrations/1/delete").with(csrf()))
                .andExpect(redirectedUrl("/integrations"))
                .andExpect(flash().attribute("success", Matchers.containsString("Removed Azure AD")));
    }
}
