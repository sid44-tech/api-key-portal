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
import com.sidbhat.apikeyportal.integration.google.GoogleIamClient;
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
    private GoogleIamClient googleIamClient;

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

    // ---------- Google: the portal makes the expiry true itself ----------

    private static final String OLD_GOOGLE_KEY =
            "{\"type\":\"service_account\",\"private_key_id\":\"old-key-id\",\"private_key\":\"old\"}";
    private static final String NEW_GOOGLE_KEY =
            "{\"type\":\"service_account\",\"private_key_id\":\"new-key-id\",\"private_key\":\"new\"}";

    /** A Google key of Sam's five days from its (portal-side) expiry, rotation switched on. */
    private ApiKey expiringGoogleKey() {
        ServiceIntegration integration = new ServiceIntegration(ServiceProvider.GOOGLE_CLOUD, "Google Cloud",
                "my-project", "portal@my-project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----");
        integration.assignTo(sam.getGroup());
        ServiceIntegration google = integrations.save(integration);

        ApiKey key = new ApiKey("Pipeline runner", "Google Cloud", OLD_GOOGLE_KEY,
                TODAY.minusDays(175), TODAY.plusDays(5));
        key.assignTo(sam, sam.getGroup());
        key.recordProvenance(google.getId(), "runner@my-project.iam.gserviceaccount.com", true, null);
        ApiKey saved = keys.save(key);

        when(googleIamClient.acquireToken("portal@my-project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----"))
                .thenReturn("gtok");
        when(googleIamClient.createServiceAccountKey("gtok", "my-project", "runner@my-project.iam.gserviceaccount.com"))
                .thenReturn(NEW_GOOGLE_KEY);
        when(googleIamClient.deleteServiceAccountKey("gtok", "my-project",
                "runner@my-project.iam.gserviceaccount.com", "old-key-id")).thenReturn(true);
        return saved;
    }

    @Test
    void aGoogleKeyIsReplacedAWeekAheadAndDeletedAtGoogleOnceItsDateHasPassed() throws Exception {
        ApiKey old = expiringGoogleKey();

        // the first half of the sweep: a second key on the same service account
        assertThat(sweep()).isEqualTo(1);
        ApiKey replacement = keys.findByRotatedFromKeyId(old.getId()).orElseThrow();
        assertThat(replacement.getKeyValue()).isEqualTo(NEW_GOOGLE_KEY);
        assertThat(replacement.getProviderResourceId()).isEqualTo("runner@my-project.iam.gserviceaccount.com");

        // the email warns that this hand-over ends in a deletion, and names the day
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getText())
                .contains("the portal will DELETE the old key there")
                .doesNotContain("old-key-id")
                .doesNotContain("new-key-id");

        // not expired yet: nothing is deleted, and the page says what is coming
        assertThat(rotationService.revokeExpiredKeys(TODAY)).isZero();
        verify(googleIamClient, org.mockito.Mockito.never()).deleteServiceAccountKey(any(), any(), any(), any());
        String before = mvc.perform(get("/keys/{id}", old.getId()).with(user(new PortalUser(sam))))
                .andReturn().getResponse().getContentAsString();
        assertThat(before).contains("when the portal deletes it at the provider");

        // the morning after its date: the old key is deleted at Google, and only it
        assertThat(rotationService.revokeExpiredKeys(old.getExpiresOn().plusDays(1))).isEqualTo(1);
        verify(googleIamClient).deleteServiceAccountKey("gtok", "my-project",
                "runner@my-project.iam.gserviceaccount.com", "old-key-id");
        verify(googleIamClient, org.mockito.Mockito.never()).deleteServiceAccount(any(), any(), any());

        ApiKey revoked = keys.findById(old.getId()).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        // the row stays, for the record; the replacement is untouched
        assertThat(keys.findById(replacement.getId())).isPresent();
        assertThat(keys.findById(replacement.getId()).orElseThrow().isRevoked()).isFalse();

        String after = mvc.perform(get("/keys/{id}", old.getId()).with(user(new PortalUser(sam))))
                .andReturn().getResponse().getContentAsString();
        assertThat(after).contains("Deleted at the provider by the portal");

        // done is done: the next sweep has nothing to delete
        assertThat(rotationService.revokeExpiredKeys(old.getExpiresOn().plusDays(2))).isZero();

        assertThat(events.findAllByOrderByOccurredAtDesc(org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent())
                .anySatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo(com.sidbhat.apikeyportal.activity.ActivityAction.KEY_REVOKED);
                    assertThat(event.getActorEmail()).isEqualTo("sam@example.com");
                });
    }

    @Test
    void switchingDeletionOffLeavesTheReplacedGoogleKeyAtGoogleAndSaysSo() throws Exception {
        ApiKey old = expiringGoogleKey();
        ServiceIntegration google = integrations.findById(old.getIntegrationId()).orElseThrow();
        UserAccount admin = accounts.findByEmailIgnoreCase("admin@example.com").orElseThrow();

        // the switch on the integrations page, as a super admin
        mvc.perform(post("/integrations/{id}/delete-replaced", google.getId()).param("delete", "false")
                        .with(user(new PortalUser(admin))).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(integrations.findById(google.getId()).orElseThrow().isDeleteReplacedKeys()).isFalse();

        // replacement still minted; the email no longer promises a deletion and warns instead
        assertThat(sweep()).isEqualTo(1);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getText())
                .contains("automatic deletion is switched off")
                .doesNotContain("the portal will DELETE");

        // and once the date passes, nothing is deleted at Google
        assertThat(rotationService.revokeExpiredKeys(old.getExpiresOn().plusDays(1))).isZero();
        verify(googleIamClient, org.mockito.Mockito.never()).deleteServiceAccountKey(any(), any(), any(), any());
        String page = mvc.perform(get("/keys/{id}", old.getId()).with(user(new PortalUser(sam))))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Deletion at the provider is switched off");
    }

    @Test
    void aGoogleKeyNobodyAskedToReplaceIsNeverDeletedWhenItsDatePasses() {
        ApiKey old = expiringGoogleKey();
        old.setAutoRotate(false);
        keys.save(old);

        assertThat(sweep()).isZero();
        assertThat(rotationService.revokeExpiredKeys(old.getExpiresOn().plusDays(1))).isZero();

        // expired in the portal, still working at Google: bookkeeping, as before,
        // because deleting somebody's only credential on a date is an outage
        verify(googleIamClient, org.mockito.Mockito.never()).deleteServiceAccountKey(any(), any(), any(), any());
        assertThat(keys.findById(old.getId()).orElseThrow().isRevoked()).isFalse();
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
