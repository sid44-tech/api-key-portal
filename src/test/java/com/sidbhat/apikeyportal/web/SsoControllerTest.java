package com.sidbhat.apikeyportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.sso.SsoConfiguration;
import com.sidbhat.apikeyportal.sso.SsoDetails;
import com.sidbhat.apikeyportal.sso.SsoException;
import com.sidbhat.apikeyportal.sso.SsoService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = SsoController.class)
@Import(SecurityConfig.class)
class SsoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SsoService ssoService;

    @MockitoBean
    private UserAccountService userService;

    @MockitoBean
    private ActivityService activityService;

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
        when(userService.findAllGroups()).thenReturn(List.of(TestUsers.platformTeam()));
        when(userService.getGroup(2L)).thenReturn(TestUsers.platformTeam());
        when(ssoService.find()).thenReturn(Optional.empty());
    }

    private SsoConfiguration saved(boolean enabled) {
        SsoConfiguration config = new SsoConfiguration("Contoso", "https://issuer.example.com", "client", "s");
        config.setEnabled(enabled);
        return config;
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/sso"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser("sam")
    void aMemberCannotReachTheSsoPage() throws Exception {
        mvc.perform(get("/sso")).andExpect(status().isForbidden());
    }

    @Test
    @WithPortalUser("alex")
    void aGroupAdminCannotReachTheSsoPageEither() throws Exception {
        // SSO decides who can get into the portal at all, so it is the super admin's alone
        mvc.perform(get("/sso")).andExpect(status().isForbidden());
        mvc.perform(post("/sso").with(csrf()).param("displayName", "Rogue")
                        .param("issuerUri", "https://evil.example.com").param("clientId", "c")
                        .param("clientSecret", "s").param("requiredGroup", "g"))
                .andExpect(status().isForbidden());

        verify(ssoService, never()).save(any(), any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void theSuperAdminSeesTheSetupPageWithTheRedirectUriToRegister() throws Exception {
        mvc.perform(get("/sso"))
                .andExpect(status().isOk())
                .andExpect(view().name("sso/settings"))
                .andExpect(content().string(Matchers.containsString("/login/oauth2/code/sso")))
                .andExpect(content().string(Matchers.containsString("Single sign-on")));
    }

    @Test
    @WithPortalUser("superadmin")
    void savingPassesTheFormThroughToTheService() throws Exception {
        when(ssoService.save(any(), any(), any())).thenReturn(saved(true));

        mvc.perform(post("/sso").with(csrf())
                        .param("enabled", "true")
                        .param("displayName", "Contoso")
                        .param("issuerUri", "https://login.microsoftonline.com/tid/v2.0")
                        .param("clientId", "client-id")
                        .param("clientSecret", "client-secret")
                        .param("scopes", "openid,profile,email")
                        .param("groupsClaim", "groups")
                        .param("requiredGroup", "portal-users")
                        .param("adminGroup", "portal-admins")
                        .param("defaultGroupId", "2"))
                .andExpect(redirectedUrl("/sso"));

        ArgumentCaptor<SsoDetails> details = ArgumentCaptor.forClass(SsoDetails.class);
        verify(ssoService).save(details.capture(), any(), any());
        assertThat(details.getValue().issuerUri()).isEqualTo("https://login.microsoftonline.com/tid/v2.0");
        assertThat(details.getValue().requiredGroup()).isEqualTo("portal-users");
        assertThat(details.getValue().adminGroup()).isEqualTo("portal-admins");
        assertThat(details.getValue().enabled()).isTrue();
    }

    @Test
    @WithPortalUser("superadmin")
    void aProviderThatCannotBeReachedIsAMessageOnTheFormNotAnErrorPage() throws Exception {
        when(ssoService.save(any(), any(), any()))
                .thenThrow(new SsoException("Could not read the OpenID configuration from https://nope"));

        mvc.perform(post("/sso").with(csrf())
                        .param("enabled", "true").param("displayName", "Contoso")
                        .param("issuerUri", "https://nope.example.com").param("clientId", "c")
                        .param("clientSecret", "s").param("requiredGroup", "portal-users")
                        .param("defaultGroupId", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("sso/settings"))
                .andExpect(content().string(Matchers.containsString("Could not read the OpenID configuration")));
    }

    @Test
    @WithPortalUser("superadmin")
    void anAccessGroupIsAlwaysRequired() throws Exception {
        mvc.perform(post("/sso").with(csrf())
                        .param("enabled", "true").param("displayName", "Contoso")
                        .param("issuerUri", "https://issuer.example.com").param("clientId", "c")
                        .param("clientSecret", "s").param("defaultGroupId", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "requiredGroup"));

        verify(ssoService, never()).save(any(), any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void turningItOnWithoutAGroupForNewAccountsIsRejected() throws Exception {
        mvc.perform(post("/sso").with(csrf())
                        .param("enabled", "true").param("displayName", "Contoso")
                        .param("issuerUri", "https://issuer.example.com").param("clientId", "c")
                        .param("clientSecret", "s").param("requiredGroup", "portal-users"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "defaultGroupChosenWhenEnabled"));

        verify(ssoService, never()).save(any(), any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void aPlainHttpIssuerIsRejected() throws Exception {
        mvc.perform(post("/sso").with(csrf())
                        .param("enabled", "true").param("displayName", "Contoso")
                        .param("issuerUri", "http://issuer.example.com").param("clientId", "c")
                        .param("clientSecret", "s").param("requiredGroup", "portal-users")
                        .param("defaultGroupId", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "issuerUri"));
    }

    @Test
    @WithPortalUser("superadmin")
    void connectingAProviderForTheFirstTimeNeedsASecret() throws Exception {
        mvc.perform(post("/sso").with(csrf())
                        .param("enabled", "true").param("displayName", "Contoso")
                        .param("issuerUri", "https://issuer.example.com").param("clientId", "c")
                        .param("requiredGroup", "portal-users").param("defaultGroupId", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "clientSecretPresentWhenNew"));
    }

    @Test
    @WithPortalUser("superadmin")
    void anExistingConnectionCanBeEditedWithoutRetypingTheSecret() throws Exception {
        when(ssoService.save(any(), any(), any())).thenReturn(saved(true));

        mvc.perform(post("/sso").with(csrf())
                        .param("secretStored", "true")
                        .param("enabled", "true").param("displayName", "Contoso renamed")
                        .param("issuerUri", "https://issuer.example.com").param("clientId", "c")
                        .param("requiredGroup", "portal-users").param("defaultGroupId", "2"))
                .andExpect(redirectedUrl("/sso"));

        verify(ssoService).save(any(), any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void switchingItOffIsRecorded() throws Exception {
        when(ssoService.find()).thenReturn(Optional.of(saved(true)));

        mvc.perform(post("/sso/disable").with(csrf()))
                .andExpect(redirectedUrl("/sso"));

        verify(ssoService).disable(any());
    }

    @Test
    @WithPortalUser("superadmin")
    void changingTheSignInSetupRequiresACsrfToken() throws Exception {
        mvc.perform(post("/sso").param("displayName", "Contoso")).andExpect(status().isForbidden());
        mvc.perform(post("/sso/disable")).andExpect(status().isForbidden());

        verify(ssoService, never()).save(any(), any(), any());
        verify(ssoService, never()).disable(any());
    }
}
