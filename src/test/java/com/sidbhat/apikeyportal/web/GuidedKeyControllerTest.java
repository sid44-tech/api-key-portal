package com.sidbhat.apikeyportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.activity.KeyOrigin;
import com.sidbhat.apikeyportal.guided.AnthropicClient;
import com.sidbhat.apikeyportal.guided.GitHubClient;
import com.sidbhat.apikeyportal.guided.GitHubClient.TokenInfo;
import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyDetails;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.security.SecurityConfig;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = GuidedKeyController.class)
@Import(SecurityConfig.class)
class GuidedKeyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private GitHubClient gitHubClient;

    @MockitoBean
    private AnthropicClient anthropicClient;

    @MockitoBean
    private UserAccountService userService;

    @BeforeEach
    void stubCurrentUser() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
    }

    @Test
    @WithPortalUser
    void showsTheGitHubWizard() throws Exception {
        mvc.perform(get("/keys/guided/github"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-github"));
    }

    @Test
    @WithPortalUser
    void verifiesGitHubTokenAndStoresItWithReportedExpiry() throws Exception {
        when(gitHubClient.verifyToken("ghp_abc"))
                .thenReturn(new TokenInfo("sidbhat", LocalDate.of(2026, 12, 31), "repo"));
        ApiKey saved = new ApiKey("CI token", "GitHub", "ghp_abc", LocalDate.now(), LocalDate.of(2026, 12, 31));
        when(apiKeyService.create(any(), any(), any())).thenReturn(saved);

        mvc.perform(post("/keys/guided/github").with(csrf())
                        .param("name", "CI token")
                        .param("keyValue", "ghp_abc"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture(), eq(TestUsers.alex()),
                eq(KeyOrigin.guidedBy("GitHub")));
        assertThat(details.getValue().getServiceName()).isEqualTo("GitHub");
        assertThat(details.getValue().getExpiresOn()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(details.getValue().getNotes()).contains("sidbhat").contains("repo");
    }

    @Test
    @WithPortalUser
    void staysOnWizardWhenVerificationFails() throws Exception {
        when(gitHubClient.verifyToken("ghp_wrong"))
                .thenThrow(new ProvisioningException("GitHub did not accept the token (HTTP 401)."));

        mvc.perform(post("/keys/guided/github").with(csrf())
                        .param("name", "CI token")
                        .param("keyValue", "ghp_wrong"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-github"))
                .andExpect(model().attributeHasErrors("form"));

        verify(apiKeyService, never()).create(any(), any(), any());
    }

    @Test
    @WithPortalUser
    void verifiesAnthropicKeyAndStoresIt() throws Exception {
        ApiKey saved = new ApiKey("Claude key", "Anthropic", "sk-ant-abc", LocalDate.now(), null);
        when(apiKeyService.create(any(), any(), any())).thenReturn(saved);

        mvc.perform(post("/keys/guided/anthropic").with(csrf())
                        .param("name", "Claude key")
                        .param("keyValue", "sk-ant-abc"))
                .andExpect(status().is3xxRedirection());

        verify(anthropicClient).verifyKey("sk-ant-abc");
        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture(), eq(TestUsers.alex()),
                eq(KeyOrigin.guidedBy("Anthropic")));
        assertThat(details.getValue().getServiceName()).isEqualTo("Anthropic");
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/keys/guided/github"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
