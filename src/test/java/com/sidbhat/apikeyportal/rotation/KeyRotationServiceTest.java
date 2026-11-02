package com.sidbhat.apikeyportal.rotation;

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

import com.sidbhat.apikeyportal.integration.KeyProvisioningService;
import com.sidbhat.apikeyportal.integration.ProvisioningException;
import com.sidbhat.apikeyportal.integration.ServiceIntegration;
import com.sidbhat.apikeyportal.integration.ServiceIntegrationRepository;
import com.sidbhat.apikeyportal.integration.ServiceProvider;
import com.sidbhat.apikeyportal.key.ApiKey;
import com.sidbhat.apikeyportal.key.ApiKeyRepository;
import com.sidbhat.apikeyportal.logging.AuditLog;
import com.sidbhat.apikeyportal.user.TestUsers;
import com.sidbhat.apikeyportal.user.UserAccount;

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

    private KeyRotationService service;

    private final UserAccount alex = TestUsers.alex();
    private final ServiceIntegration azure = new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
            "tenant-1", "client", "secret");

    @BeforeEach
    void setUp() {
        service = new KeyRotationService(keys, integrations, preferences, provisioning, mailer,
                new RotationProperties(true, "0 0 7 * * *", 7), audit);
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

        verify(mailer).send(key, minted, true);
        verify(audit).allowed(eq("key_rotated"), eq("alex@example.com"),
                eq("Azure AD / Reporting service"), any());
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
                ServiceProvider.GOOGLE_CLOUD, "GCP", "project", "client", "secret")));

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
        verify(mailer, never()).send(eq(failing), any(), anyBoolean());
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
        verify(mailer).send(key, key, false);
        verify(audit).allowed(eq("key_rotated"), any(), any(),
                contains("previous value was revoked"));
    }

    @Test
    void theKeyPageDoesNotPromiseAnOverlapGitLabWillNotGive() {
        ApiKey key = expiringGitLabKey();
        when(integrations.findById(7L)).thenReturn(Optional.of(gitlab));

        assertThat(service.supportsRotation(key)).isTrue();
        assertThat(service.rotationOverlaps(key)).isFalse();
    }
}
