package com.sidbhat.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.logging.AuditContextFilter;
import com.sidbhat.apikeyportal.logging.AuditLogContext;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * What the portal writes down while it is being used, against the real
 * application: the events, the context on each line, and - the one that matters
 * most on a portal full of secrets - what never appears in a log at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailIntegrationTest {

    private static final String SECRET = "sk_live_fake0123456789";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Catches everything, not just the audit logger, so "no secrets anywhere" means anywhere. */
    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    private UserAccount sam;
    private UserAccount outsider;

    @BeforeEach
    void setUp() {
        UserGroup platform = groups.save(new UserGroup("Platform team"));
        UserGroup other = groups.save(new UserGroup("Another team"));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz",
                passwordEncoder.encode("a-good-long-password"), UserRole.MEMBER, platform));
        outsider = accounts.save(new UserAccount("jo@example.com", "Jo Okafor",
                passwordEncoder.encode("a-good-long-password"), UserRole.GROUP_ADMIN, other));

        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void cleanUp() {
        rootLogger.detachAppender(appender);
        keys.deleteAll();
        accounts.deleteAll(List.of(sam, outsider));
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName()))
                .toList());
    }

    private RequestPostProcessor as(UserAccount account) {
        return user(new PortalUser(accounts.findById(account.getId()).orElseThrow()));
    }

    private List<String> auditLines() {
        return appender.list.stream()
                .filter(event -> "portal.audit".equals(event.getLoggerName()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private ILoggingEvent firstEventWith(String fragment) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No log line containing '" + fragment + "' in "
                        + auditLines()));
    }

    private Long addAKey() throws Exception {
        mvc.perform(post("/keys").with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", SECRET).param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());
        return keys.findAll().get(0).getId();
    }

    // ---------- what gets written down ----------

    @Test
    void revealingAKeyIsRecordedWithWhoWhatAndHow() throws Exception {
        Long keyId = addAKey();

        mvc.perform(get("/keys/{id}/value", keyId).param("intent", "copied").with(as(sam)))
                .andExpect(status().isOk());

        assertThat(auditLines()).anySatisfy(line -> assertThat(line)
                .contains("event=key_revealed")
                .contains("outcome=ok")
                .contains("actor=sam@example.com")
                .contains("detail=\"Copied to the clipboard\""));
    }

    @Test
    void reachingForAnotherGroupsKeyIsRecordedAsARefusal() throws Exception {
        Long keyId = addAKey();

        mvc.perform(get("/keys/{id}", keyId).with(as(outsider))).andExpect(status().isForbidden());

        // the thing a portal of secrets most wants to know: who tried what they
        // are not allowed to have
        assertThat(auditLines()).anySatisfy(line -> assertThat(line)
                .contains("event=access_denied")
                .contains("outcome=refused")
                .contains("actor=jo@example.com")
                .contains("another group"));
    }

    @Test
    void aRefusalIsAWarningSoItCanBeAlertedOn() throws Exception {
        Long keyId = addAKey();

        mvc.perform(get("/keys/{id}", keyId).with(as(outsider))).andExpect(status().isForbidden());

        assertThat(firstEventWith("event=access_denied").getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void signingInAndOutAreBothRecorded() throws Exception {
        mvc.perform(formLogin().user("sam@example.com").password("a-good-long-password"));
        mvc.perform(post("/logout").with(as(sam)).with(csrf()));

        assertThat(auditLines())
                .anySatisfy(line -> assertThat(line).contains("event=sign_in").contains("outcome=ok"))
                .anySatisfy(line -> assertThat(line).contains("event=sign_out"));
    }

    @Test
    void aWrongPasswordIsRecordedWithoutThePasswordThatWasTried() throws Exception {
        mvc.perform(formLogin().user("sam@example.com").password("the-wrong-one-entirely"));

        assertThat(auditLines()).anySatisfy(line -> assertThat(line)
                .contains("event=sign_in")
                .contains("outcome=refused")
                .contains("actor=sam@example.com"));
        assertThat(auditLines()).noneSatisfy(line ->
                assertThat(line).contains("the-wrong-one-entirely"));
    }

    @Test
    void editingAKeyRecordsTheFieldsThatChangedAndNotTheNewValue() throws Exception {
        Long keyId = addAKey();

        mvc.perform(post("/keys/{id}", keyId).with(as(sam)).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod secret")
                        .param("keyValue", "sk_live_rotated9876543210").param("createdOn", "2026-01-01"))
                .andExpect(status().is3xxRedirection());

        assertThat(auditLines()).anySatisfy(line -> assertThat(line)
                .contains("event=key_updated")
                .contains("detail=\"Changed value\""));
        assertThat(appender.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("sk_live_rotated9876543210"));
    }

    // ---------- the context on every line ----------

    @Test
    void everyLineSaysWhoDidItAndFromWhere() throws Exception {
        Long keyId = addAKey();
        appender.list.clear();

        mvc.perform(get("/keys/{id}/value", keyId).with(as(sam))).andExpect(status().isOk());

        ILoggingEvent event = firstEventWith("event=key_revealed");
        assertThat(event.getMDCPropertyMap())
                .containsEntry(AuditLogContext.USER, "sam@example.com")
                .containsEntry(AuditLogContext.CLIENT_IP, "127.0.0.1")
                .containsEntry(AuditLogContext.REQUEST, "GET /keys/" + keyId + "/value");
        assertThat(event.getMDCPropertyMap().get(AuditLogContext.REQUEST_ID)).isNotBlank();
    }

    @Test
    void thatRequestIdComesBackToTheBrowserSoItCanBeQuoted() throws Exception {
        mvc.perform(get("/keys").with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(header().exists(AuditContextFilter.REQUEST_ID_HEADER));
    }

    @Test
    void twoRequestsAreToldApart() throws Exception {
        Long keyId = addAKey();
        appender.list.clear();

        mvc.perform(get("/keys/{id}/value", keyId).with(as(sam)));
        mvc.perform(get("/keys/{id}/value", keyId).with(as(sam)));

        List<String> ids = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("event=key_revealed"))
                .map(event -> event.getMDCPropertyMap().get(AuditLogContext.REQUEST_ID))
                .toList();
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void theContextIsClearedSoTheNextRequestOnThisThreadIsNotLoggedAsThisUser() throws Exception {
        mvc.perform(get("/keys").with(as(sam))).andExpect(status().isOk());

        // the thread goes back to the pool with whatever is left on it
        assertThat(MDC.get(AuditLogContext.USER)).isNull();
        assertThat(MDC.get(AuditLogContext.REQUEST_ID)).isNull();
        assertThat(MDC.get(AuditLogContext.CLIENT_IP)).isNull();
    }

    // ---------- what is never written down ----------

    @Test
    void noKeyValueEverReachesAnyLogLine() throws Exception {
        // With Spring's web logging turned all the way up, which is the first
        // thing anybody does when a request misbehaves. Its message converters
        // log the body they are about to write - "Writing [{value=sk_live_...}]"
        // - so the reveal endpoint writes its own response rather than handing
        // one back to be converted.
        Logger springWeb = (Logger) LoggerFactory.getLogger("org.springframework.web");
        Level previous = springWeb.getLevel();
        springWeb.setLevel(Level.TRACE);
        try {
            revealAndDeleteAKey();
        } finally {
            springWeb.setLevel(previous);
        }

        assertThat(appender.list.stream()
                .map(event -> event.getLoggerName() + " :: " + event.getFormattedMessage())
                .filter(line -> line.contains(SECRET))
                .toList()).isEmpty();
    }

    /** Everything that handles a key value in one go. */
    private void revealAndDeleteAKey() throws Exception {
        Long keyId = addAKey();
        mvc.perform(get("/keys/{id}/value", keyId).with(as(sam))).andExpect(status().isOk());
        mvc.perform(get("/keys/{id}/value", keyId).param("intent", "copied").with(as(sam)));
        mvc.perform(get("/keys/{id}", keyId).with(as(sam)));
        mvc.perform(post("/keys/{id}/delete", keyId).with(as(sam)).with(csrf()));
    }

    @Test
    void theValueEndpointStillReturnsTheValueItIsAskedFor() throws Exception {
        Long keyId = addAKey();

        // it writes its own response body, so this is worth checking outright
        mvc.perform(get("/keys/{id}/value", keyId).with(as(sam)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.value").value(SECRET));
    }

    @Test
    void noPasswordReachesAnyLogLine() throws Exception {
        mvc.perform(formLogin().user("sam@example.com").password("a-good-long-password"));
        mvc.perform(post("/profile/password").with(as(sam)).with(csrf())
                .param("currentPassword", "a-good-long-password")
                .param("newPassword", "another-good-long-password")
                .param("confirmPassword", "another-good-long-password"));

        assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("a-good-long-password"));
        assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("another-good-long-password"));
    }

    @Test
    void changingAPasswordIsRecordedEvenThoughThePasswordIsNot() throws Exception {
        mvc.perform(post("/profile/password").with(as(sam)).with(csrf())
                .param("currentPassword", "a-good-long-password")
                .param("newPassword", "another-good-long-password")
                .param("confirmPassword", "another-good-long-password"));

        assertThat(auditLines()).anySatisfy(line -> assertThat(line)
                .contains("event=password_change")
                .contains("actor=sam@example.com"));
    }
}
