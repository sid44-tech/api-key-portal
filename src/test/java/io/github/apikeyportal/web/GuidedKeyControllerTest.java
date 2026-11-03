package io.github.apikeyportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.apikeyportal.activity.KeyOrigin;
import io.github.apikeyportal.guided.AnthropicClient;
import io.github.apikeyportal.guided.AtlassianClient;
import io.github.apikeyportal.guided.OpenAiClient;
import io.github.apikeyportal.guided.StripeClient;
import io.github.apikeyportal.guided.GitHubClient;
import io.github.apikeyportal.guided.GitHubClient.TokenInfo;
import io.github.apikeyportal.integration.ProvisioningException;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyDetails;
import io.github.apikeyportal.key.ApiKeyService;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.security.SecurityConfig;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.TestUsers;
import io.github.apikeyportal.user.UserAccountService;
import io.github.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = GuidedKeyController.class)
@Import({SecurityConfig.class, AuditLog.class})
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
    private AtlassianClient atlassianClient;

    @MockitoBean
    private OpenAiClient openAiClient;

    @MockitoBean
    private StripeClient stripeClient;

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

    // ---------- Atlassian ----------

    @Test
    @WithPortalUser
    void showsTheAtlassianWizard() throws Exception {
        mvc.perform(get("/keys/guided/atlassian"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-atlassian"))
                .andExpect(content().string(Matchers.containsString("id.atlassian.com")))
                // the email half is the thing people get wrong, so the page leads with it
                .andExpect(content().string(Matchers.containsString("email plus token")));
    }

    @Test
    @WithPortalUser
    void verifiesTheEmailAndTokenTogetherAndStoresOnlyTheToken() throws Exception {
        when(atlassianClient.verifyToken("alex@example.com", "atl-token"))
                .thenReturn(new AtlassianClient.AccountInfo("5b10a2", "alex@example.com", "Alex Carter"));
        when(apiKeyService.create(any(), any(), any()))
                .thenReturn(new ApiKey("Jira bot", "Atlassian", "atl-token", LocalDate.now(), null));

        mvc.perform(post("/keys/guided/atlassian").with(csrf())
                        .param("name", "Jira bot")
                        .param("accountEmail", "alex@example.com")
                        .param("keyValue", "atl-token"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture(), eq(TestUsers.alex()),
                eq(KeyOrigin.guidedBy("Atlassian")));
        assertThat(details.getValue().getServiceName()).isEqualTo("Atlassian");
        // the secret half is the value; the email is context, not a secret
        assertThat(details.getValue().getKeyValue()).isEqualTo("atl-token");
        assertThat(details.getValue().getNotes())
                .contains("Alex Carter")
                .contains("alex@example.com")
                .doesNotContain("atl-token");
    }

    @Test
    @WithPortalUser
    void refusesToVerifyAnAtlassianTokenWithoutItsEmail() throws Exception {
        mvc.perform(post("/keys/guided/atlassian").with(csrf())
                        .param("name", "Jira bot")
                        .param("keyValue", "atl-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-atlassian"))
                .andExpect(model().attributeHasFieldErrors("form", "accountEmail"));

        // nothing is sent to Atlassian, and nothing is stored
        verifyNoInteractions(atlassianClient);
        verify(apiKeyService, never()).create(any(), any(), any());
    }

    @Test
    @WithPortalUser
    void staysOnTheAtlassianWizardWhenTheProviderRefuses() throws Exception {
        when(atlassianClient.verifyToken(any(), any()))
                .thenThrow(new ProvisioningException("Atlassian did not accept the email and token together"));

        mvc.perform(post("/keys/guided/atlassian").with(csrf())
                        .param("name", "Jira bot")
                        .param("accountEmail", "alex@example.com")
                        .param("keyValue", "wrong"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-atlassian"))
                .andExpect(model().attributeHasErrors("form"));

        verify(apiKeyService, never()).create(any(), any(), any());
    }

    // ---------- OpenAI ----------

    @Test
    @WithPortalUser
    void showsTheOpenAiWizard() throws Exception {
        mvc.perform(get("/keys/guided/openai"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-openai"))
                .andExpect(content().string(Matchers.containsString("platform.openai.com")));
    }

    @Test
    @WithPortalUser
    void verifiesOpenAiKeyAndStoresIt() throws Exception {
        when(apiKeyService.create(any(), any(), any()))
                .thenReturn(new ApiKey("GPT key", "OpenAI", "sk-proj-abc", LocalDate.now(), null));

        mvc.perform(post("/keys/guided/openai").with(csrf())
                        .param("name", "GPT key")
                        .param("keyValue", "sk-proj-abc"))
                .andExpect(status().is3xxRedirection());

        verify(openAiClient).verifyKey("sk-proj-abc");
        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture(), eq(TestUsers.alex()),
                eq(KeyOrigin.guidedBy("OpenAI")));
        assertThat(details.getValue().getServiceName()).isEqualTo("OpenAI");
    }

    @Test
    @WithPortalUser
    void staysOnTheOpenAiWizardWhenVerificationFails() throws Exception {
        org.mockito.Mockito.doThrow(new ProvisioningException("OpenAI did not accept the key (HTTP 401)."))
                .when(openAiClient).verifyKey("sk-wrong");

        mvc.perform(post("/keys/guided/openai").with(csrf())
                        .param("name", "GPT key")
                        .param("keyValue", "sk-wrong"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-openai"))
                .andExpect(model().attributeHasErrors("form"));

        verify(apiKeyService, never()).create(any(), any(), any());
    }

    // ---------- Stripe ----------

    @Test
    @WithPortalUser
    void showsTheStripeWizard() throws Exception {
        mvc.perform(get("/keys/guided/stripe"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-stripe"))
                .andExpect(content().string(Matchers.containsString("dashboard.stripe.com")))
                .andExpect(content().string(Matchers.containsString("Create restricted key")));
    }

    @Test
    @WithPortalUser
    void verifiesStripeKeyAndRecordsThatItIsLive() throws Exception {
        when(stripeClient.verifyKey("rk_live_abc")).thenReturn(
                new StripeClient.AccountInfo("acct_123", new StripeClient.BusinessProfile("Acme Ltd"), true));
        when(apiKeyService.create(any(), any(), any()))
                .thenReturn(new ApiKey("Billing", "Stripe", "rk_live_abc", LocalDate.now(), null));

        mvc.perform(post("/keys/guided/stripe").with(csrf())
                        .param("name", "Billing")
                        .param("keyValue", "rk_live_abc"))
                .andExpect(status().is3xxRedirection());

        ArgumentCaptor<ApiKeyDetails> details = ArgumentCaptor.forClass(ApiKeyDetails.class);
        verify(apiKeyService).create(details.capture(), eq(TestUsers.alex()),
                eq(KeyOrigin.guidedBy("Stripe")));
        assertThat(details.getValue().getServiceName()).isEqualTo("Stripe");
        // a live key and a test key look identical in a list; the notes say which
        assertThat(details.getValue().getNotes())
                .contains("acct_123").contains("Acme Ltd").contains("LIVE")
                .doesNotContain("rk_live_abc");
    }

    @Test
    @WithPortalUser
    void storesARestrictedKeyThatCouldNotReadItsAccount() throws Exception {
        when(stripeClient.verifyKey("rk_live_narrow"))
                .thenReturn(new StripeClient.AccountInfo(null, null, null));
        when(apiKeyService.create(any(), any(), any()))
                .thenReturn(new ApiKey("Billing", "Stripe", "rk_live_narrow", LocalDate.now(), null));

        mvc.perform(post("/keys/guided/stripe").with(csrf())
                        .param("name", "Billing")
                        .param("keyValue", "rk_live_narrow"))
                .andExpect(status().is3xxRedirection());

        verify(apiKeyService).create(any(), any(), eq(KeyOrigin.guidedBy("Stripe")));
    }

    @Test
    @WithPortalUser
    void staysOnTheStripeWizardWhenVerificationFails() throws Exception {
        when(stripeClient.verifyKey("rk_wrong"))
                .thenThrow(new ProvisioningException("Stripe did not accept the key (HTTP 401)."));

        mvc.perform(post("/keys/guided/stripe").with(csrf())
                        .param("name", "Billing")
                        .param("keyValue", "rk_wrong"))
                .andExpect(status().isOk())
                .andExpect(view().name("keys/guided-stripe"))
                .andExpect(model().attributeHasErrors("form"));

        verify(apiKeyService, never()).create(any(), any(), any());
    }
}
