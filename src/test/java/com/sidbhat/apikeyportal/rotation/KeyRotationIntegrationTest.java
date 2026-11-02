package com.sidbhat.apikeyportal.rotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

import com.sidbhat.apikeyportal.activity.ActivityEventRepository;
import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationRepository;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.integration.azure.AzureGraphClient;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.notification.ExpiryReminderService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountRepository;
import com.sidbhat.apikeyportal.user.UserGroup;
import com.sidbhat.apikeyportal.user.UserGroupRepository;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Automatic replacement end to end against the real database: the sweep, the
 * stored replacement, the email, what the list shows, and what the reminders
 * stop doing. Only the provider itself is stood in for.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KeyRotationIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KeyRotationService rotationService;

    @Autowired
    private ExpiryReminderService reminderService;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private ServiceIntegrationRepository integrations;

    @Autowired
    private AutoRotationPreferenceRepository preferences;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private ActivityEventRepository events;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private AzureGraphClient graphClient;

    @MockitoBean
    private JavaMailSender mailSender;

    private UserAccount sam;
    private ServiceIntegration azure;
    private ApiKey expiring;

    @BeforeEach
    void aProvisionedKeyFiveDaysFromExpiry() {
        UserGroup group = groups.save(new UserGroup("Platform team"));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz",
                passwordEncoder.encode("a-good-long-password"), UserRole.MEMBER, group));
        ServiceIntegration integration = new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD production",
                "tenant-1", "portal-client", "portal-secret");
        integration.assignTo(group);
        azure = integrations.save(integration);

        ApiKey key = new ApiKey("Reporting service", "Azure AD production", "old-secret-value",
                TODAY.minusDays(175), TODAY.plusDays(5));
        key.assignTo(sam, group);
        key.recordProvenance(azure.getId(), "app-object-1", null, null);
        expiring = keys.save(key);

        when(graphClient.acquireToken("tenant-1", "portal-client", "portal-secret")).thenReturn("tok");
        when(graphClient.addPassword(eq("tok"), eq("app-object-1"), eq("Reporting service"), any(Instant.class)))
                .thenReturn(new AzureGraphClient.CreatedSecret("fresh-secret-value", null));
    }

    @AfterEach
    void cleanUp() {
        keys.deleteAll();
        integrations.deleteAll();
        preferences.deleteAll();
        events.deleteAll();
        accounts.delete(sam);
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName()))
                .toList());
    }

    private int sweep() {
        return rotationService.rotateDueKeys(TODAY);
    }

    private ApiKey storedReplacement() {
        return keys.findByRotatedFromKeyId(expiring.getId()).orElseThrow();
    }

    @Test
    void nothingHappensUntilTheOwnerOptsIn() {
        assertThat(sweep()).isZero();
        assertThat(keys.findByRotatedFromKeyId(expiring.getId())).isEmpty();
    }

    @Test
    void theSweepMintsStoresAndAnnouncesAReplacement() {
        rotationService.setPreference(sam, ServiceProvider.AZURE_AD, true);

        assertThat(sweep()).isEqualTo(1);

        ApiKey replacement = storedReplacement();
        assertThat(replacement.getKeyValue()).isEqualTo("fresh-secret-value");
        assertThat(replacement.getName()).isEqualTo("Reporting service");
        assertThat(replacement.getGroup().getId()).isEqualTo(expiring.getGroup().getId());
        assertThat(replacement.getOwner().getId()).isEqualTo(sam.getId());
        // the replacement is itself rotatable when its own time comes
        assertThat(replacement.getIntegrationId()).isEqualTo(azure.getId());
        assertThat(replacement.getProviderResourceId()).isEqualTo("app-object-1");

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("sam@example.com");
        assertThat(mail.getValue().getText())
                .contains("Your key is about to expire and a new one has been generated")
                .doesNotContain("old-secret-value")
                .doesNotContain("fresh-secret-value");
    }

    @Test
    void runningTheSweepTwiceMintsExactlyOneReplacement() {
        rotationService.setPreference(sam, ServiceProvider.AZURE_AD, true);

        assertThat(sweep()).isEqualTo(1);
        assertThat(sweep()).isZero();

        assertThat(keys.findByRotatedFromKeyIdIn(List.of(expiring.getId()))).hasSize(1);
    }

    @Test
    void aReplacedKeyGetsNoSeparateExpiryReminder() {
        rotationService.setPreference(sam, ServiceProvider.AZURE_AD, true);
        sweep();

        // the 7-days-out reminder would fire today; the rotation email covered it.
        // (the replacement itself is half a year from expiry, so nothing else is due)
        assertThat(reminderService.sendDueReminders(TODAY.plusDays(expiring.getExpiresOn()
                .toEpochDay() - TODAY.toEpochDay() - 7))).isZero();
    }

    @Test
    void theListPointsBothWaysAcrossTheHandOver() throws Exception {
        rotationService.setPreference(sam, ServiceProvider.AZURE_AD, true);
        sweep();

        String html = mvc.perform(get("/keys").with(user(new PortalUser(sam))))
                .andReturn().getResponse().getContentAsString();

        // the expiring key says its successor exists; the successor points back
        assertThat(html).contains("Replacement ready");
        assertThat(html).contains("Replaces an expiring key");
        assertThat(html).contains("/keys/" + storedReplacement().getId());
    }

    @Test
    void anExpiredKeyIsHiddenUntilAskedFor() throws Exception {
        ApiKey dead = new ApiKey("Old key", "Stripe", "sk_dead", TODAY.minusYears(1), TODAY.minusDays(3));
        dead.assignTo(sam, sam.getGroup());
        keys.save(dead);

        String byDefault = mvc.perform(get("/keys").with(user(new PortalUser(sam))))
                .andReturn().getResponse().getContentAsString();
        String askedFor = mvc.perform(get("/keys").param("showExpired", "true")
                        .with(user(new PortalUser(sam))))
                .andReturn().getResponse().getContentAsString();

        assertThat(byDefault).doesNotContain("Old key");
        assertThat(byDefault).contains("Show expired");
        assertThat(askedFor).contains("Old key");
    }

    @Test
    void theKeyNotYetExpiredStaysInTheActiveListAfterRotation() throws Exception {
        rotationService.setPreference(sam, ServiceProvider.AZURE_AD, true);
        sweep();

        // not expired yet, so still in the default view - alongside its successor
        String html = mvc.perform(get("/keys").with(user(new PortalUser(sam))))
                .andReturn().getResponse().getContentAsString();
        int occurrences = html.split("Reporting service", -1).length - 1;
        assertThat(occurrences).isGreaterThanOrEqualTo(2);
    }

    @Test
    void pinningAKeyOffOnItsPageStopsItsRotation() throws Exception {
        rotationService.setPreference(sam, ServiceProvider.AZURE_AD, true);

        mvc.perform(post("/keys/{id}/rotation", expiring.getId())
                        .param("autoRotate", "false")
                        .with(user(new PortalUser(sam))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(sweep()).isZero();
        assertThat(keys.findByRotatedFromKeyId(expiring.getId())).isEmpty();
    }

    @Test
    void theHistoryRecordsTheReplacementAsAutomatic() {
        rotationService.setPreference(sam, ServiceProvider.AZURE_AD, true);
        sweep();

        assertThat(events.findAllByOrderByOccurredAtDesc(org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent())
                .anySatisfy(event -> {
                    assertThat(event.getKeySource()).isEqualTo(com.sidbhat.apikeyportal.activity.KeySource.ROTATED);
                    assertThat(event.getActorEmail()).isEqualTo("sam@example.com");
                });
    }
}
