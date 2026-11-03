package io.github.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.apikeyportal.alerts.AlertCard;
import io.github.apikeyportal.alerts.AlertChannelRepository;
import io.github.apikeyportal.alerts.AlertException;
import io.github.apikeyportal.alerts.ChannelProvider;
import io.github.apikeyportal.alerts.WebhookClient;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.notification.ExpiryReminderRepository;
import io.github.apikeyportal.notification.ExpiryReminderService;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

/**
 * A group's chat channels end to end: connected from the integrations page in
 * either product, stored encrypted, posted to by the reminder sweep with no
 * mail server in sight, and disconnected again. Only the webhooks are stood in for.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AlertChannelIntegrationTest {

    private static final String TEAMS_HOOK = "https://prod-12.westus.logic.azure.com/workflows/abc/triggers/manual/paths/invoke";
    private static final String SLACK_HOOK = "https://hooks.slack.com/services/T000/B000/XXXX";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AlertChannelRepository channels;

    @Autowired
    private ExpiryReminderService sweep;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private ExpiryReminderRepository reminders;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private WebhookClient client;

    private UserGroup team;
    private UserAccount alex;
    private UserAccount sam;

    @BeforeEach
    void setUp() {
        team = groups.save(new UserGroup("Platform team"));
        alex = accounts.save(new UserAccount("alex@example.com", "Alex Carter",
                passwordEncoder.encode("a-good-long-password"), UserRole.GROUP_ADMIN, team));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz",
                passwordEncoder.encode("a-good-long-password"), UserRole.MEMBER, team));
    }

    @AfterEach
    void cleanUp() {
        channels.deleteAll();
        reminders.deleteAll();
        keys.deleteAll();
        accounts.delete(alex);
        accounts.delete(sam);
        groups.delete(team);
    }

    private void connect(String path, String name, String hook) throws Exception {
        mvc.perform(post("/integrations/alerts/" + path).with(user(new PortalUser(alex))).with(csrf())
                        .param("name", name).param("webhookUrl", hook)
                        .param("enabled", "true").param("notifyExpiries", "true")
                        .param("notifyRotationFailures", "true"))
                .andExpect(redirectedUrl("/integrations"));
    }

    @Test
    void aGroupCanHaveBothATeamsAndASlackChannelEachProvenByATestCard() throws Exception {
        connect("teams", "#platform-alerts", TEAMS_HOOK);
        connect("slack", "#platform", SLACK_HOOK);

        ArgumentCaptor<AlertCard> card = ArgumentCaptor.forClass(AlertCard.class);
        verify(client).post(eq(ChannelProvider.TEAMS), eq(TEAMS_HOOK), card.capture());
        verify(client).post(eq(ChannelProvider.SLACK), eq(SLACK_HOOK), any());
        assertThat(card.getValue().title()).contains("is connected to this channel");

        // both stored, both encrypted, neither URL anywhere on a page
        assertThat(channels.count()).isEqualTo(2);
        assertThat(jdbc.queryForList("select webhook_url from alert_channel", String.class))
                .noneMatch(raw -> raw.contains("logic.azure.com") || raw.contains("hooks.slack.com"));
        String page = mvc.perform(get("/integrations").with(user(new PortalUser(alex))))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Microsoft Teams").contains("#platform-alerts").contains("Slack").contains("#platform")
                .doesNotContain(TEAMS_HOOK).doesNotContain(SLACK_HOOK)
                // nothing left to connect
                .doesNotContain("alerts/teams\" class=\"btn btn-primary").doesNotContain("alerts/slack\" class=\"btn btn-primary");
        assertThat(mvc.perform(get("/integrations/alerts/slack").with(user(new PortalUser(alex))))
                .andReturn().getResponse().getContentAsString()).doesNotContain(SLACK_HOOK).contains("Leave blank to keep it");
    }

    @Test
    void nothingIsStoredWhenTheServiceRefusesTheTestCard() throws Exception {
        doThrow(new AlertException("Slack rejected the message (HTTP 404): no_service"))
                .when(client).post(eq(ChannelProvider.SLACK), any(), any());

        mvc.perform(post("/integrations/alerts/slack").with(user(new PortalUser(alex))).with(csrf())
                        .param("name", "#platform").param("webhookUrl", SLACK_HOOK))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("no_service")));

        assertThat(channels.count()).isZero();
    }

    @Test
    void aPrivateAddressIsRefusedBeforeAnythingIsPosted() throws Exception {
        mvc.perform(post("/integrations/alerts/teams").with(user(new PortalUser(alex))).with(csrf())
                        .param("name", "#platform-alerts").param("webhookUrl", "https://10.0.0.5/hooks"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("local or private")));

        verify(client, never()).post(any(), any(), any());
        assertThat(channels.count()).isZero();
    }

    @Test
    void aMemberCannotConnectAndAnUnknownProductIsNotFound() throws Exception {
        mvc.perform(get("/integrations/alerts/slack").with(user(new PortalUser(sam)))).andExpect(status().isForbidden());
        mvc.perform(post("/integrations/alerts/slack").with(user(new PortalUser(sam))).with(csrf())
                        .param("name", "x").param("webhookUrl", SLACK_HOOK))
                .andExpect(status().isForbidden());
        mvc.perform(get("/integrations/alerts/discord").with(user(new PortalUser(alex)))).andExpect(status().isNotFound());
        verify(client, never()).post(any(), any(), any());
    }

    @Test
    void theReminderSweepPostsExpiriesToBothChannelsWithNoMailServerAtAll() throws Exception {
        connect("teams", "#platform-alerts", TEAMS_HOOK);
        connect("slack", "#platform", SLACK_HOOK);
        ApiKey key = new ApiKey("Deploy token", "GitHub", "ghp_SECRET", LocalDate.now().minusDays(100),
                LocalDate.now().plusDays(7));
        key.assignTo(sam, team);
        keys.save(key);

        // the test context has no JavaMailSender: before chat channels this sweep did nothing
        assertThat(sweep.sendDueReminders(LocalDate.now())).isEqualTo(1);

        ArgumentCaptor<AlertCard> card = ArgumentCaptor.forClass(AlertCard.class);
        verify(client, org.mockito.Mockito.times(2)).post(eq(ChannelProvider.SLACK), eq(SLACK_HOOK), card.capture());
        AlertCard expiry = card.getAllValues().get(1);
        assertThat(expiry.title()).isEqualTo("Key expires in 7 days: GitHub / Deploy token");
        assertThat(expiry.toSlack().toString()).doesNotContain("ghp_SECRET");
        // announced once: the second sweep of the day has nothing to post
        assertThat(sweep.sendDueReminders(LocalDate.now())).isZero();
    }

    @Test
    void disconnectingOneProductLeavesTheOther() throws Exception {
        connect("teams", "#platform-alerts", TEAMS_HOOK);
        connect("slack", "#platform", SLACK_HOOK);

        mvc.perform(post("/integrations/alerts/teams/delete").with(user(new PortalUser(alex))).with(csrf()))
                .andExpect(redirectedUrl("/integrations"));

        assertThat(channels.count()).isEqualTo(1);
        assertThat(channels.findAll().get(0).getProvider()).isEqualTo(ChannelProvider.SLACK);
        String page = mvc.perform(get("/integrations").with(user(new PortalUser(alex))))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("#platform-alerts").contains("#platform");
    }
}
