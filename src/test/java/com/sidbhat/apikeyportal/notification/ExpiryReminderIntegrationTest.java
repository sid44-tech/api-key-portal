package com.sidbhat.apikeyportal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * The whole reminder story against the real database, with only the mail server
 * stubbed: the sweep finds the right keys, sends once per threshold, respects
 * the mute switch, and the switch itself works from the key's page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExpiryReminderIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    @Autowired
    private MockMvc mvc;

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

    /** No real SMTP in tests; its presence is what makes the sweep run at all. */
    @MockitoBean
    private JavaMailSender mailSender;

    private UserAccount alex;
    private UserAccount sam;

    @BeforeEach
    void setUp() {
        UserGroup team = groups.save(new UserGroup("Platform team"));
        alex = accounts.save(new UserAccount("alex@example.com", "Alex Carter",
                passwordEncoder.encode("correct-horse-battery"), UserRole.GROUP_ADMIN, team));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz",
                passwordEncoder.encode("correct-horse-battery"), UserRole.MEMBER, team));
    }

    @AfterEach
    void cleanUp() {
        reminders.deleteAll();
        keys.deleteAll();
        accounts.deleteAll(accounts.findAllByOrderByDisplayNameAsc().stream()
                .filter(a -> !a.isSuperAdmin()).toList());
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName())).toList());
    }

    private ApiKey keyOwnedBy(UserAccount owner, String name, LocalDate expiresOn) {
        ApiKey key = new ApiKey(name, "GitHub", "ghp_fake_" + name, TODAY.minusDays(100), expiresOn);
        key.assignTo(owner, owner.getGroup());
        return keys.save(key);
    }

    private SimpleMailMessage theOneEmailSent() {
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        return message.getValue();
    }

    @Test
    void theOwnerIsEmailedAboutAKeyInsideAThresholdAndOnlyOnce() {
        ApiKey key = keyOwnedBy(alex, "Deploy", TODAY.plusDays(6));

        assertThat(sweep.sendDueReminders(TODAY)).isEqualTo(1);
        // the next day, same band: nothing
        assertThat(sweep.sendDueReminders(TODAY.plusDays(1))).isZero();

        SimpleMailMessage email = theOneEmailSent();
        assertThat(email.getTo()).containsExactly("alex@example.com");
        assertThat(email.getText()).contains("Deploy").contains("/keys/" + key.getId());
        assertThat(email.getText()).doesNotContain("ghp_fake_Deploy");
    }

    @Test
    void theFourRemindersArriveAsTheDateApproaches() {
        keyOwnedBy(alex, "Deploy", TODAY.plusDays(40));

        assertThat(sweep.sendDueReminders(TODAY)).isZero();                // 40 days: too early
        assertThat(sweep.sendDueReminders(TODAY.plusDays(10))).isEqualTo(1); // 30 days
        assertThat(sweep.sendDueReminders(TODAY.plusDays(20))).isZero();     // 20 days: same band
        assertThat(sweep.sendDueReminders(TODAY.plusDays(26))).isEqualTo(1); // 14 days
        assertThat(sweep.sendDueReminders(TODAY.plusDays(33))).isEqualTo(1); // 7 days
        assertThat(sweep.sendDueReminders(TODAY.plusDays(39))).isEqualTo(1); // 1 day
        assertThat(sweep.sendDueReminders(TODAY.plusDays(41))).isZero();     // expired: nothing
        verify(mailSender, times(4)).send(any(SimpleMailMessage.class));
    }

    @Test
    void aMutedKeyIsLeftAlone() {
        ApiKey key = keyOwnedBy(alex, "Deploy", TODAY.plusDays(6));
        key.setExpiryNotificationsMuted(true);
        keys.save(key);

        assertThat(sweep.sendDueReminders(TODAY)).isZero();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void changingTheExpiryDateStartsTheRemindersOver() {
        ApiKey key = keyOwnedBy(alex, "Deploy", TODAY.plusDays(6));
        assertThat(sweep.sendDueReminders(TODAY)).isEqualTo(1);

        // rotated: same key, new expiry a year out, then a year passes
        key.setExpiresOn(TODAY.plusDays(371));
        keys.save(key);
        assertThat(sweep.sendDueReminders(TODAY.plusDays(365))).isEqualTo(1);
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void aKeyThatNeverExpiresIsNeverMentioned() {
        keyOwnedBy(alex, "Forever", null);

        assertThat(sweep.sendDueReminders(TODAY)).isZero();
    }

    @Test
    void aDisabledOwnerIsNotEmailed() {
        keyOwnedBy(sam, "Deploy", TODAY.plusDays(6));
        sam.setEnabled(false);
        accounts.save(sam);

        assertThat(sweep.sendDueReminders(TODAY)).isZero();
    }

    @Test
    void theOwnerMutesFromTheKeyPageAndSeesItReflected() throws Exception {
        ApiKey key = keyOwnedBy(alex, "Deploy", TODAY.plusDays(6));

        mvc.perform(get("/keys/{id}", key.getId()).with(user(new PortalUser(alex))))
                .andExpect(content().string(containsString("Mute expiration notifications")))
                .andExpect(content().string(containsString("a month, two weeks, a week and a day")));

        mvc.perform(post("/keys/{id}/notifications", key.getId()).with(user(new PortalUser(alex))).with(csrf())
                        .param("muted", "true"))
                .andExpect(redirectedUrl("/keys/" + key.getId()));

        assertThat(keys.findById(key.getId()).orElseThrow().isExpiryNotificationsMuted()).isTrue();
        mvc.perform(get("/keys/{id}", key.getId()).with(user(new PortalUser(alex))))
                .andExpect(content().string(containsString("Muted.")));
        assertThat(sweep.sendDueReminders(TODAY)).isZero();
    }

    @Test
    void aColleagueSeesNoSwitchAndCannotFlipIt() throws Exception {
        ApiKey key = keyOwnedBy(alex, "Deploy", TODAY.plusDays(6));

        mvc.perform(get("/keys/{id}", key.getId()).with(user(new PortalUser(sam))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("Mute expiration notifications"))));
        mvc.perform(post("/keys/{id}/notifications", key.getId()).with(user(new PortalUser(sam))).with(csrf())
                        .param("muted", "true"))
                .andExpect(status().isForbidden());

        assertThat(keys.findById(key.getId()).orElseThrow().isExpiryNotificationsMuted()).isFalse();
    }

    @Test
    void remindersAreOnByDefaultForANewKey() throws Exception {
        mvc.perform(post("/keys").with(user(new PortalUser(alex))).with(csrf())
                        .param("serviceName", "Stripe").param("name", "Prod")
                        .param("keyValue", "sk_live_fake0123456789")
                        .param("createdOn", TODAY.toString()).param("expiresOn", TODAY.plusDays(6).toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(keys.findAll()).singleElement()
                .satisfies(key -> assertThat(key.isExpiryNotificationsMuted()).isFalse());
        assertThat(sweep.sendDueReminders(TODAY)).isEqualTo(1);
    }
}
