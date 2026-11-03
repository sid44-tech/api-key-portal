package io.github.apikeyportal.rotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.apikeyportal.integration.KeyProvisioningService;
import io.github.apikeyportal.integration.ProvisioningException;
import io.github.apikeyportal.integration.ServiceIntegration;
import io.github.apikeyportal.integration.ServiceIntegrationRepository;
import io.github.apikeyportal.integration.ServiceProvider;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.user.TestUsers;
import io.github.apikeyportal.user.UserAccount;

/**
 * Who gets a replacement and who doesn't. The provider calls themselves are
 * {@link KeyProvisioningService}'s and tested there; this is the eligibility
 * and settings logic the sweep runs on.
 */
@ExtendWith(MockitoExtension.class)
class KeyRotationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    @Mock
    private ApiKeyRepository keys;

    @Mock
    private ServiceIntegrationRepository integrations;

    @Mock
    private AutoRotationPreferenceRepository preferences;

    @Mock
    private KeyProvisioningService provisioning;

    @Mock
    private KeyRotationMailer mailer;

    @Mock
    private AuditLog audit;

    @Mock
    private io.github.apikeyportal.alerts.AlertNotifier alerts;

    private KeyRotationService service;

    private final UserAccount alex = TestUsers.alex();
    private final ServiceIntegration azure = new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
            "tenant-1", "client", "secret");

    @BeforeEach
    void setUp() {
        service = new KeyRotationService(keys, integrations, preferences, provisioning, mailer,
                new RotationProperties(true, "0 0 7 * * *", 7), audit, alerts);
    }

    /** An expiring provisioned key of Alex's, id 42, minted through integration 7. */
    private ApiKey expiring() {
        ApiKey key = new ApiKey("Reporting service", "Azure AD", "old-secret",
                TODAY.minusDays(175), TODAY.plusDays(5));
        key.assignTo(alex, alex.getGroup());
        key.recordProvenance(7L, "obj-1", null, null);
        ReflectionTestUtils.setField(key, "id", 42L);
        return key;
    }

    private ApiKey replacement() {
        return new ApiKey("Reporting service", "Azure AD", "fresh", TODAY, TODAY.plusDays(180));
    }

    private void candidates(ApiKey... found) {
        when(keys.findRotationCandidates(TODAY, TODAY.plusDays(7))).thenReturn(List.of(found));
    }

    private void prefersRotation(boolean enabled) {
        when(preferences.findByUserIdAndProvider(alex.getId(), ServiceProvider.AZURE_AD))
                .thenReturn(Optional.of(new AutoRotationPreference(alex.getId(), ServiceProvider.AZURE_AD, enabled)));
    }

    @Test
    void replacesAnExpiringKeyWhoseOwnerOptedIn() {
        ApiKey key = expiring();
        candidates(key);
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));
        prefersRotation(true);
        ApiKey minted = replacement();
        when(provisioning.rotate(key, azure)).thenReturn(minted);

        assertThat(service.rotateDueKeys(TODAY)).isEqualTo(1);

        // Azure retires the secret itself, and the integration tidies it away afterwards by default
        verify(mailer).send(key, minted, ServiceProvider.AZURE_AD, true);
        verify(audit).allowed(eq("key_rotated"), eq("alex@example.com"),
                eq("Azure AD / Reporting service"), any());
        // a success wipes any failure the dashboard was showing for this key
        verify(keys).clearRotationFailure(42L);
    }

    @Test
    void nobodyIsOptedInByDefault() {
        candidates(expiring());
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));
        when(preferences.findByUserIdAndProvider(alex.getId(), ServiceProvider.AZURE_AD))
                .thenReturn(Optional.empty());

        assertThat(service.rotateDueKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning, mailer);
    }

    @Test
    void aKeyPinnedOnIsReplacedEvenWithTheProviderSettingOff() {
        ApiKey key = expiring();
        key.setAutoRotate(true);
        candidates(key);
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));
        when(provisioning.rotate(key, azure)).thenReturn(replacement());

        assertThat(service.rotateDueKeys(TODAY)).isEqualTo(1);
        // the override answered; the provider setting was never consulted
        verifyNoInteractions(preferences);
    }

    @Test
    void aKeyPinnedOffIsLeftAloneEvenWithTheProviderSettingOn() {
        ApiKey key = expiring();
        key.setAutoRotate(false);
        candidates(key);
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));

        assertThat(service.rotateDueKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning, mailer);
    }

    @Test
    void aKeyWhoseIntegrationWasRemovedIsSkipped() {
        candidates(expiring());
        when(integrations.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.rotateDueKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning);
    }

    @Test
    void aSwitchedOffIntegrationMintsNoReplacementsEither() {
        ApiKey key = expiring();
        key.setAutoRotate(true);
        candidates(key);
        azure.setEnabled(false);
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));

        assertThat(service.rotateDueKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning, mailer);
    }

    @Test
    void providersWithoutRotationSupportAreSkipped() {
        candidates(expiring());
        when(integrations.findById(7L)).thenReturn(Optional.of(new ServiceIntegration(
                ServiceProvider.CLOUDFLARE, "Cloudflare DNS", "tmpl-1", "tmpl-1", "cf-admin")));

        assertThat(service.rotateDueKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning);
    }

    @Test
    void aProviderRefusalIsRecordedAndDoesNotStopTheSweep() {
        ApiKey failing = expiring();
        ApiKey fine = expiring();
        ReflectionTestUtils.setField(fine, "id", 43L);
        candidates(failing, fine);
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));
        prefersRotation(true);
        when(provisioning.rotate(failing, azure)).thenThrow(new ProvisioningException("Azure said no"));
        when(provisioning.rotate(fine, azure)).thenReturn(replacement());

        assertThat(service.rotateDueKeys(TODAY)).isEqualTo(1);

        // the failure is on the record and tried again next sweep; no email went
        // out claiming a replacement that doesn't exist
        verify(audit).refused(eq("key_rotated"), eq("alex@example.com"), any(), eq("Azure said no"));
        verify(mailer, never()).send(eq(failing), any(), any(), anyBoolean());
        // and the failure lands on the key, where the dashboard can list it, and
        // goes to the group's Teams channel if it has one
        verify(keys).markRotationFailed(eq(42L), any(), eq("Azure said no"));
        verify(alerts).rotationFailed(eq(failing), eq("Azure said no"));
        verify(alerts, never()).rotationFailed(eq(fine), any());
        verify(keys, never()).markRotationFailed(eq(43L), any(), any());
    }

    @Test
    void anUnexpectedErrorIsThisKeysProblemNotTheMornings() {
        ApiKey failing = expiring();
        ApiKey fine = expiring();
        ReflectionTestUtils.setField(fine, "id", 43L);
        candidates(failing, fine);
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));
        prefersRotation(true);
        // not a provider refusal: the mint worked and the save blew up, or any
        // other surprise - the kind of error that used to abandon the whole sweep
        when(provisioning.rotate(failing, azure))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database is away"));
        when(provisioning.rotate(fine, azure)).thenReturn(replacement());

        assertThat(service.rotateDueKeys(TODAY)).isEqualTo(1);

        // the second key still got its replacement, and the failure is on the
        // record to be retried tomorrow
        verify(mailer).send(eq(fine), any(), eq(ServiceProvider.AZURE_AD), anyBoolean());
        verify(audit).refused(eq("key_rotated"), eq("alex@example.com"), any(),
                contains("database is away"));
        verify(mailer, never()).send(eq(failing), any(), any(), anyBoolean());
    }

    @Test
    void flippingThePreferenceIsSavedAndAudited() {
        when(preferences.findByUserIdAndProvider(alex.getId(), ServiceProvider.AZURE_AD))
                .thenReturn(Optional.empty());

        service.setPreference(alex, ServiceProvider.AZURE_AD, true);

        verify(preferences).save(any(AutoRotationPreference.class));
        verify(audit).allowed(eq("auto_rotation_changed"), eq("alex@example.com"), any(), eq("Switched on"));
    }

    // ---------- providers whose rotation revokes the old credential ----------

    private final ServiceIntegration gitlab = new ServiceIntegration(ServiceProvider.GITLAB, "GitLab CI",
            "https://gitlab.example.com", "group/app", "glpat-admin");

    /** A GitLab key of Alex's expiring in five days, pinned to rotate. */
    private ApiKey expiringGitLabKey() {
        ApiKey key = new ApiKey("CI token", "GitLab CI", "glpat-old", TODAY.minusDays(360), TODAY.plusDays(5));
        key.assignTo(alex, alex.getGroup());
        key.recordProvenance(7L, "99", true, null);
        ReflectionTestUtils.setField(key, "id", 55L);
        return key;
    }

    @Test
    void aGitLabKeyIsLeftAloneUntilItIsNearlyExpired() {
        ApiKey key = expiringGitLabKey();
        candidates(key);
        when(integrations.findById(7L)).thenReturn(Optional.of(gitlab));

        // five days out: rotating now would revoke a working token five days early
        assertThat(service.rotateDueKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning, mailer);
    }

    @Test
    void aGitLabKeyIsRotatedOnTheLastSweepBeforeItExpires() {
        ApiKey key = expiringGitLabKey();
        ReflectionTestUtils.setField(key, "expiresOn", TODAY.plusDays(1));
        candidates(key);
        when(integrations.findById(7L)).thenReturn(Optional.of(gitlab));
        when(provisioning.rotate(key, gitlab)).thenReturn(key);

        assertThat(service.rotateDueKeys(TODAY)).isEqualTo(1);

        // the email must say "already changed", not "a replacement is ready"
        verify(mailer).send(key, key, ServiceProvider.GITLAB, false);
        verify(audit).allowed(eq("key_rotated"), any(), any(),
                contains("previous value was revoked"));
    }

    @Test
    void theKeyPageDoesNotPromiseAnOverlapGitLabWillNotGive() {
        ApiKey key = expiringGitLabKey();
        when(integrations.findById(7L)).thenReturn(Optional.of(gitlab));

        assertThat(service.supportsRotation(key)).isTrue();
        assertThat(service.rotationOverlaps(key)).isFalse();
        // in-place rotation leaves nothing behind, so there is nothing to delete
        assertThat(service.deletesReplaced(key)).isFalse();
    }

    // ---------- providers whose keys the portal has to delete itself ----------

    private final ServiceIntegration google = new ServiceIntegration(ServiceProvider.GOOGLE_CLOUD, "Google Cloud",
            "my-project", "portal@my-project.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----");

    /** A Google key of Alex's that expired yesterday. */
    private ApiKey expiredGoogleKey() {
        ApiKey key = new ApiKey("Reporting service", "Google Cloud", "{\"private_key_id\":\"old\"}",
                TODAY.minusDays(181), TODAY.minusDays(1));
        key.assignTo(alex, alex.getGroup());
        key.recordProvenance(7L, "reporting@my-project.iam.gserviceaccount.com", null, null);
        ReflectionTestUtils.setField(key, "id", 71L);
        return key;
    }

    @Test
    void aGoogleKeyRotatesWithAnOverlapAndTheAuditSaysTheDeletionIsComing() {
        ApiKey key = expiring();
        candidates(key);
        when(integrations.findById(7L)).thenReturn(Optional.of(google));
        when(preferences.findByUserIdAndProvider(alex.getId(), ServiceProvider.GOOGLE_CLOUD))
                .thenReturn(Optional.of(new AutoRotationPreference(alex.getId(), ServiceProvider.GOOGLE_CLOUD, true)));
        ApiKey minted = replacement();
        when(provisioning.rotate(key, google)).thenReturn(minted);

        // five days out and overlapping, so it goes now - like Azure, unlike GitLab
        assertThat(service.rotateDueKeys(TODAY)).isEqualTo(1);

        verify(mailer).send(key, minted, ServiceProvider.GOOGLE_CLOUD, true);
        verify(audit).allowed(eq("key_rotated"), eq("alex@example.com"), any(),
                contains("will be deleted at Google Cloud after it expires on " + key.getExpiresOn()));
        assertThat(service.deletesReplaced(key)).isTrue();
        assertThat(service.providerNeverExpires(key)).isTrue();
    }

    @Test
    void withDeletionSwitchedOffTheEmailAndAuditSayTheOldKeyKeepsWorking() {
        ApiKey key = expiring();
        candidates(key);
        google.setDeleteReplacedKeys(false);
        when(integrations.findById(7L)).thenReturn(Optional.of(google));
        when(preferences.findByUserIdAndProvider(alex.getId(), ServiceProvider.GOOGLE_CLOUD))
                .thenReturn(Optional.of(new AutoRotationPreference(alex.getId(), ServiceProvider.GOOGLE_CLOUD, true)));
        when(provisioning.rotate(key, google)).thenReturn(replacement());

        assertThat(service.rotateDueKeys(TODAY)).isEqualTo(1);

        // the replacement is still minted; what changes is what the owner is told
        verify(mailer).send(eq(key), any(), eq(ServiceProvider.GOOGLE_CLOUD), eq(false));
        verify(audit).allowed(eq("key_rotated"), any(), any(), contains("deletion at Google Cloud is switched off"));
        assertThat(service.deletesReplaced(key)).isFalse();
        assertThat(service.providerNeverExpires(key)).isTrue();
    }

    @Test
    void anExpiredReplacedGoogleKeyIsDeletedAtTheProvider() {
        ApiKey key = expiredGoogleKey();
        when(keys.findRevocationCandidates(TODAY)).thenReturn(List.of(key));
        when(integrations.findById(7L)).thenReturn(Optional.of(google));

        assertThat(service.revokeExpiredKeys(TODAY)).isEqualTo(1);

        verify(provisioning).revoke(key, google);
        // the owner's settings are not consulted again: the rotation they asked
        // for is simply being finished
        verifyNoInteractions(preferences, mailer);
    }

    @Test
    void theDeletionSweepTidiesTheExpiredAzureSecretAwayToo() {
        ApiKey key = expiring();
        ReflectionTestUtils.setField(key, "expiresOn", TODAY.minusDays(1));
        when(keys.findRevocationCandidates(TODAY)).thenReturn(List.of(key));
        when(integrations.findById(7L)).thenReturn(Optional.of(azure));

        // Azure has already stopped the secret; this takes the dead entry off the registration
        assertThat(service.revokeExpiredKeys(TODAY)).isEqualTo(1);
        verify(provisioning).revoke(key, azure);
    }

    @Test
    void anIntegrationWithDeletionSwitchedOffLeavesTheExpiredKeyAtTheProvider() {
        ApiKey key = expiredGoogleKey();
        google.setDeleteReplacedKeys(false);
        when(keys.findRevocationCandidates(TODAY)).thenReturn(List.of(key));
        when(integrations.findById(7L)).thenReturn(Optional.of(google));

        assertThat(service.revokeExpiredKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning);
    }

    @Test
    void aSwitchedOffIntegrationDeletesNothingEither() {
        when(keys.findRevocationCandidates(TODAY)).thenReturn(List.of(expiredGoogleKey()));
        google.setEnabled(false);
        when(integrations.findById(7L)).thenReturn(Optional.of(google));

        assertThat(service.revokeExpiredKeys(TODAY)).isZero();
        verifyNoInteractions(provisioning);
    }

    @Test
    void anUnexpectedErrorDeletingOneKeyDoesNotLeaveTheRestAliveAtTheirProviders() {
        ApiKey failing = expiredGoogleKey();
        ApiKey fine = expiredGoogleKey();
        ReflectionTestUtils.setField(fine, "id", 72L);
        when(keys.findRevocationCandidates(TODAY)).thenReturn(List.of(failing, fine));
        when(integrations.findById(7L)).thenReturn(Optional.of(google));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("database is away"))
                .when(provisioning).revoke(failing, google);

        assertThat(service.revokeExpiredKeys(TODAY)).isEqualTo(1);

        verify(provisioning).revoke(fine, google);
        verify(audit).refused(eq("key_revoked"), eq("alex@example.com"), any(),
                contains("database is away"));
    }

    @Test
    void aRefusedDeletionIsRecordedAndTriedAgainNextSweep() {
        ApiKey failing = expiredGoogleKey();
        ApiKey fine = expiredGoogleKey();
        ReflectionTestUtils.setField(fine, "id", 72L);
        when(keys.findRevocationCandidates(TODAY)).thenReturn(List.of(failing, fine));
        when(integrations.findById(7L)).thenReturn(Optional.of(google));
        org.mockito.Mockito.doThrow(new ProvisioningException("Permission iam.serviceAccountKeys.delete denied"))
                .when(provisioning).revoke(failing, google);

        assertThat(service.revokeExpiredKeys(TODAY)).isEqualTo(1);

        verify(audit).refused(eq("key_revoked"), eq("alex@example.com"), any(),
                eq("Permission iam.serviceAccountKeys.delete denied"));
        verify(provisioning).revoke(fine, google);
        verify(keys).markRotationFailed(eq(71L), any(), contains("Could not delete at the provider"));
        verify(keys).clearRotationFailure(72L);
    }
}
