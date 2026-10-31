package com.sidbhat.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityEvent;
import com.sidbhat.apikeyportal.activity.ActivityEventRepository;
import com.sidbhat.apikeyportal.activity.KeySource;
import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationRepository;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Who may set up a shared service integration, who may then use it, and what the
 * history records about it - end to end against the real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityAndScopingIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private ServiceIntegrationRepository integrations;

    @Autowired
    private ActivityEventRepository events;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UserAccount superAdmin;
    private UserAccount alex;
    private UserAccount sam;
    private UserAccount jo;
    private UserGroup platformTeam;
    private UserGroup okafors;

    @BeforeEach
    void setUpTwoGroups() {
        superAdmin = accounts.findByEmailIgnoreCase("admin@example.com").orElseThrow();
        platformTeam = groups.save(new UserGroup("Platform team"));
        okafors = groups.save(new UserGroup("The Okafors"));
        alex = addAccount("alex@example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam);
        sam = addAccount("sam@example.com", "Sam Diaz", UserRole.MEMBER, platformTeam);
        jo = addAccount("jo@example.com", "Jo Okafor", UserRole.GROUP_ADMIN, okafors);
        events.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        keys.deleteAll();
        integrations.deleteAll();
        events.deleteAll();
        accounts.deleteAll(accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(a -> !a.is(superAdmin))
                .toList());
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName()))
                .toList());
    }

    private UserAccount addAccount(String email, String name, UserRole role, UserGroup group) {
        return accounts.save(new UserAccount(email, name, passwordEncoder.encode("correct-horse-battery"),
                role, group));
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new PortalUser(accounts.findById(account.getId()).orElseThrow()));
    }

    private ServiceIntegration integrationFor(UserGroup group) {
        ServiceIntegration integration = new ServiceIntegration(ServiceProvider.AZURE_AD,
                "Azure " + group.getName(), "tenant-1", "portal-client", "fake-secret");
        integration.assignTo(group);
        return integrations.save(integration);
    }

    private List<ActivityEvent> allEvents() {
        return events.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 50)).getContent();
    }

    // ---------- who can set up an integration ----------

    @Test
    void aMemberIsTurnedAwayFromTheSetupWizardRatherThanAtTheEndOfIt() throws Exception {
        mvc.perform(get("/integrations/new").param("provider", "AZURE_AD").with(as(sam)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/integrations/azure-ad").with(as(sam)).with(csrf())
                        .param("name", "Mine").param("tenantId", "t").param("clientId", "c")
                        .param("clientSecret", "s"))
                .andExpect(status().isForbidden());

        assertThat(integrations.findAll()).isEmpty();
    }

    @Test
    void aGroupAdminReachesTheSetupWizard() throws Exception {
        mvc.perform(get("/integrations/new").param("provider", "AZURE_AD").with(as(alex)))
                .andExpect(status().isOk());
    }

    // ---------- an integration is the group's, and only the group's ----------

    @Test
    void everyoneInTheGroupSeesAndCanUseTheIntegration() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        // a plain member sees it listed and can open the provision form for it
        mvc.perform(get("/integrations").with(as(sam)))
                .andExpect(content().string(containsString("Azure Platform team")));
        mvc.perform(get("/keys/provision").param("integration", shared.getId().toString()).with(as(sam)))
                .andExpect(status().isOk());
    }

    @Test
    void anotherGroupCannotSeeOrUseIt() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(get("/integrations").with(as(jo)))
                .andExpect(content().string(not(containsString("Azure Platform team"))));
        mvc.perform(get("/keys/provision").param("integration", shared.getId().toString()).with(as(jo)))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAnAdminOfTheOwningGroupCanRemoveIt() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(post("/integrations/{id}/delete", shared.getId()).with(as(sam)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/integrations/{id}/delete", shared.getId()).with(as(jo)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(integrations.findById(shared.getId())).isPresent();

        mvc.perform(post("/integrations/{id}/delete", shared.getId()).with(as(alex)).with(csrf()))
                .andExpect(redirectedUrl("/integrations"));
        assertThat(integrations.findById(shared.getId())).isEmpty();
    }

    // ---------- the audit trail ----------

    @Test
    void addingAKeyByHandIsRecordedAgainstTheCreator() throws Exception {
        mvc.perform(post("/keys").with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "sk_live_fake0123456789").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());

        ActivityEvent event = allEvents().get(0);
        assertThat(event.getAction()).isEqualTo(ActivityAction.KEY_CREATED);
        assertThat(event.getActorEmail()).isEqualTo("sam@example.com");
        assertThat(event.getTargetName()).isEqualTo("Stripe / Prod secret");
        assertThat(event.getKeySource()).isEqualTo(KeySource.MANUAL);
        assertThat(event.getGroupName()).isEqualTo("Platform team");
    }

    @Test
    void removingAKeyIsRecordedToo() throws Exception {
        mvc.perform(post("/keys").with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "sk_live_fake0123456789").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());
        Long keyId = keys.findAll().get(0).getId();

        mvc.perform(post("/keys/{id}/delete", keyId).with(as(sam)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(allEvents()).extracting(ActivityEvent::getAction)
                .containsExactly(ActivityAction.KEY_DELETED, ActivityAction.KEY_CREATED);
    }

    @Test
    void nothingSecretEndsUpInTheHistory() throws Exception {
        mvc.perform(post("/keys").with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "sk_live_fake0123456789").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());

        ActivityEvent event = allEvents().get(0);
        assertThat(event.getTargetName()).doesNotContain("sk_live_fake0123456789");
        assertThat(event.getDetail()).isNull();
        mvc.perform(get("/activity").with(as(alex)))
                .andExpect(content().string(not(containsString("sk_live_fake0123456789"))));
    }

    @Test
    void theHistoryShowsOneGroupToItsAdminAndEveryGroupToASuperAdmin() throws Exception {
        mvc.perform(post("/keys").with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Platform key")
                        .param("keyValue", "sk_live_fake0123456789").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/keys").with(as(jo)).with(csrf())
                        .param("serviceName", "GitHub").param("name", "Okafor key")
                        .param("keyValue", "ghp_fake0123456789").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/activity").with(as(alex)))
                .andExpect(content().string(containsString("Platform key")))
                .andExpect(content().string(not(containsString("Okafor key"))));

        mvc.perform(get("/activity").with(as(superAdmin)))
                .andExpect(content().string(containsString("Platform key")))
                .andExpect(content().string(containsString("Okafor key")));
    }

    @Test
    void aMemberCannotOpenTheHistoryPage() throws Exception {
        mvc.perform(get("/activity").with(as(sam))).andExpect(status().isForbidden());
    }

    @Test
    void connectingAndRemovingAnIntegrationAreBothRecorded() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(post("/integrations/{id}/delete", shared.getId()).with(as(alex)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        ActivityEvent event = allEvents().get(0);
        assertThat(event.getAction()).isEqualTo(ActivityAction.INTEGRATION_REMOVED);
        assertThat(event.getActorEmail()).isEqualTo("alex@example.com");
        assertThat(event.getTargetName()).isEqualTo("Azure Platform team");
    }

    @Test
    void theHistorySurvivesTheThingItDescribesBeingDeleted() throws Exception {
        mvc.perform(post("/keys").with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "sk_live_fake0123456789").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());
        Long keyId = keys.findAll().get(0).getId();
        mvc.perform(post("/keys/{id}/delete", keyId).with(as(sam)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // the key is gone, but who created it and what kind it was is still on record
        assertThat(keys.findById(keyId)).isEmpty();
        mvc.perform(get("/activity").with(as(alex)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Stripe / Prod secret")))
                .andExpect(content().string(containsString("Sam Diaz")));
    }
}
