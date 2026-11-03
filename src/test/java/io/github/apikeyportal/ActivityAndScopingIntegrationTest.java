package io.github.apikeyportal;

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

import io.github.apikeyportal.activity.ActivityAction;
import io.github.apikeyportal.activity.ActivityEvent;
import io.github.apikeyportal.activity.ActivityEventRepository;
import io.github.apikeyportal.activity.KeySource;
import io.github.apikeyportal.integration.ServiceIntegration;
import io.github.apikeyportal.integration.ServiceIntegrationRepository;
import io.github.apikeyportal.integration.ServiceProvider;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

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

    private List<ActivityEvent> eventsOf(ActivityAction action) {
        return allEvents().stream().filter(event -> event.getAction() == action).toList();
    }

    /** Adds a key through the portal the way its owner would, and returns its id. */
    private Long addAKeyAs(UserAccount owner) throws Exception {
        mvc.perform(post("/keys").with(as(owner)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "sk_live_fake0123456789").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());
        return keys.findAll().get(0).getId();
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

    // ---------- switching an integration off for the group ----------

    private boolean enabledInDb(Long id) {
        return integrations.findById(id).orElseThrow().isEnabled();
    }

    @Test
    void aGroupAdminCanSwitchTheirOwnGroupsIntegrationOffAndOn() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(alex)).with(csrf()))
                .andExpect(redirectedUrl("/integrations"));
        assertThat(enabledInDb(shared.getId())).isFalse();

        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(alex)).with(csrf())
                        .param("enabled", "true"))
                .andExpect(redirectedUrl("/integrations"));
        assertThat(enabledInDb(shared.getId())).isTrue();
    }

    @Test
    void neitherAMemberNorAnotherGroupsAdminCanTouchTheSwitch() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(sam)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(jo)).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(enabledInDb(shared.getId())).isTrue();
    }

    @Test
    void aSuperAdminCanSwitchAnyGroupsIntegration() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(superAdmin)).with(csrf()))
                .andExpect(redirectedUrl("/integrations"));

        assertThat(enabledInDb(shared.getId())).isFalse();
    }

    @Test
    void aSwitchedOffIntegrationMintsNothingForAnybody() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);
        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(alex)).with(csrf()));

        // a member posting the provision form directly, offered or not
        mvc.perform(post("/keys/provision").with(as(sam)).with(csrf())
                        .param("integrationId", shared.getId().toString())
                        .param("name", "Sneaky key")
                        .param("validity", "DAYS_180"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("switched off by a group admin")));

        assertThat(keys.findAll()).isEmpty();
    }

    @Test
    void theAddKeyPageStopsOfferingASwitchedOffIntegration() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(get("/keys/new").with(as(sam)))
                .andExpect(content().string(containsString(shared.getName())));

        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(alex)).with(csrf()));

        mvc.perform(get("/keys/new").with(as(sam)))
                .andExpect(content().string(not(containsString(shared.getName()))));
        // but the integrations page still shows it, marked, so it can come back
        mvc.perform(get("/integrations").with(as(sam)))
                .andExpect(content().string(containsString(shared.getName())))
                .andExpect(content().string(containsString("Switched off")));
    }

    @Test
    void flippingTheSwitchIsOnTheRecord() throws Exception {
        ServiceIntegration shared = integrationFor(platformTeam);

        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(alex)).with(csrf()));
        mvc.perform(post("/integrations/{id}/enabled", shared.getId()).with(as(alex)).with(csrf())
                .param("enabled", "true"));

        assertThat(allEvents()).extracting(ActivityEvent::getAction)
                .contains(ActivityAction.INTEGRATION_DISABLED, ActivityAction.INTEGRATION_ENABLED);
        assertThat(allEvents()).filteredOn(e -> e.getAction() == ActivityAction.INTEGRATION_DISABLED)
                .allSatisfy(e -> assertThat(e.getActorEmail()).isEqualTo("alex@example.com"));
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
    void revealingAKeyIsRecordedAgainstWhoeverRevealedIt() throws Exception {
        Long keyId = addAKeyAs(sam);

        mvc.perform(post("/keys/{id}/value", keyId).with(csrf()).with(as(sam))).andExpect(status().isOk());

        ActivityEvent event = eventsOf(ActivityAction.KEY_REVEALED).get(0);
        assertThat(event.getActorEmail()).isEqualTo("sam@example.com");
        assertThat(event.getTargetName()).isEqualTo("Stripe / Prod secret");
        assertThat(event.getTargetId()).isEqualTo(keyId);
        assertThat(event.getDetail()).isEqualTo("Shown on screen");
    }

    @Test
    void copyingAKeyIsRecordedAsSuch() throws Exception {
        Long keyId = addAKeyAs(sam);

        mvc.perform(post("/keys/{id}/value", keyId).with(csrf()).param("intent", "copied").with(as(sam)))
                .andExpect(status().isOk());

        assertThat(eventsOf(ActivityAction.KEY_REVEALED).get(0).getDetail())
                .isEqualTo("Copied to the clipboard");
    }

    @Test
    void everyRevealIsKeptSeparatelyRatherThanOverwritingTheLastOne() throws Exception {
        Long keyId = addAKeyAs(sam);

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/keys/{id}/value", keyId).with(csrf()).with(as(sam))).andExpect(status().isOk());
        }

        // "last revealed" on the key cannot answer who took the value out and when
        assertThat(eventsOf(ActivityAction.KEY_REVEALED)).hasSize(3);
    }

    @Test
    void aRefusedRevealIsNotRecordedAsOne() throws Exception {
        Long keyId = addAKeyAs(sam);

        // Alex can see Sam's key listed, but never its value
        mvc.perform(post("/keys/{id}/value", keyId).with(csrf()).with(as(alex))).andExpect(status().isForbidden());

        assertThat(eventsOf(ActivityAction.KEY_REVEALED)).isEmpty();
    }

    @Test
    void aRevealForgedByAnotherSiteIsRefusedAndRecordsNothing() throws Exception {
        Long keyId = addAKeyAs(sam);

        // what a cross-site request looks like: the owner's session, no CSRF
        // token - which another origin cannot read. As a GET it is not a
        // reveal at all any more, and as a token-less POST it is refused.
        mvc.perform(get("/keys/{id}/value", keyId).with(as(sam)))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/keys/{id}/value", keyId).with(as(sam)))
                .andExpect(status().isForbidden());

        // the history is what an audit relies on; neither attempt touched it
        assertThat(eventsOf(ActivityAction.KEY_REVEALED)).isEmpty();
    }

    @Test
    void theRevealedValueNeverReachesTheHistory() throws Exception {
        Long keyId = addAKeyAs(sam);

        mvc.perform(post("/keys/{id}/value", keyId).with(csrf()).with(as(sam))).andExpect(status().isOk());

        assertThat(allEvents()).allSatisfy(event -> {
            assertThat(event.getTargetName()).doesNotContain("sk_live_fake0123456789");
            assertThat(String.valueOf(event.getDetail())).doesNotContain("sk_live_fake0123456789");
        });
        mvc.perform(get("/activity").with(as(alex)))
                .andExpect(content().string(containsString("Key revealed")))
                .andExpect(content().string(not(containsString("sk_live_fake0123456789"))));
    }

    @Test
    void editingAKeyRecordsWhatWasChanged() throws Exception {
        Long keyId = addAKeyAs(sam);

        mvc.perform(post("/keys/{id}", keyId).with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "").param("createdOn", "2026-01-01")
                        .param("expiresOn", "2027-01-01").param("notes", "Rotated by hand"))
                .andExpect(status().is3xxRedirection());

        ActivityEvent event = eventsOf(ActivityAction.KEY_UPDATED).get(0);
        assertThat(event.getActorEmail()).isEqualTo("sam@example.com");
        assertThat(event.getTargetName()).isEqualTo("Stripe / Prod secret");
        assertThat(event.getDetail()).isEqualTo("Changed expiry date, notes");
    }

    @Test
    void replacingAValueSaysThatItChangedAndNothingMore() throws Exception {
        Long keyId = addAKeyAs(sam);

        mvc.perform(post("/keys/{id}", keyId).with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "sk_live_rotated9876543210").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());

        ActivityEvent event = eventsOf(ActivityAction.KEY_UPDATED).get(0);
        assertThat(event.getDetail()).isEqualTo("Changed value");
        assertThat(event.getDetail()).doesNotContain("sk_live_rotated9876543210");
    }

    @Test
    void savingTheEditFormWithoutChangingAnythingRecordsNothing() throws Exception {
        Long keyId = addAKeyAs(sam);

        mvc.perform(post("/keys/{id}", keyId).with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());

        assertThat(eventsOf(ActivityAction.KEY_UPDATED)).isEmpty();
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
