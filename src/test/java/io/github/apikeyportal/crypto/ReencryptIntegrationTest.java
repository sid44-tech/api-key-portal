package io.github.apikeyportal.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.apikeyportal.integration.ServiceIntegration;
import io.github.apikeyportal.integration.ServiceIntegrationRepository;
import io.github.apikeyportal.integration.ServiceProvider;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.key.ApiKeyRepository;
import io.github.apikeyportal.logging.AuditLog;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountRepository;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import io.github.apikeyportal.user.UserRole;

/**
 * Rotating the encryption key against the real database: values written under
 * the old key, a new key made active, the rewrite, and - the only thing that
 * ultimately matters - every secret still readable afterwards, and readable
 * with the new key alone.
 */
@SpringBootTest
class ReencryptIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApiKeyRepository keys;

    @Autowired
    private ServiceIntegrationRepository integrations;

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private AuditLog audit;

    /** The key the application context (and so the stored rows) was built with. */
    private static final String ORIGINAL_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private UserAccount sam;
    private Long keyId;
    private Long integrationId;

    private static String freshKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** A service wired to whichever keys this test wants, over the real database. */
    private ReencryptService serviceWith(Map<String, String> named, String active) {
        return new ReencryptService(jdbc,
                new EncryptionService(new EncryptionProperties(ORIGINAL_KEY, named, active)), audit);
    }

    @BeforeEach
    void storeSomeSecrets() {
        UserGroup group = groups.save(new UserGroup("Platform team"));
        sam = accounts.save(new UserAccount("sam@example.com", "Sam Diaz", "hash", UserRole.MEMBER, group));

        ApiKey key = new ApiKey("Prod secret", "Stripe", "sk_live_fake0123456789",
                LocalDate.now(), LocalDate.now().plusDays(90));
        key.assignTo(sam, group);
        keyId = keys.save(key).getId();

        ServiceIntegration integration = new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
                "tenant-1", "client-1", "provider-credential");
        integration.assignTo(group);
        integrationId = integrations.save(integration).getId();
    }

    @AfterEach
    void cleanUp() {
        // Raw SQL: these rows have deliberately been re-encrypted with keys the
        // running application context does not hold, so loading them as
        // entities would (correctly) fail to decrypt. Deleting the ciphertext
        // does not need to read it.
        jdbc.update("delete from api_key");
        jdbc.update("delete from service_integration");
        accounts.delete(sam);
        groups.deleteAll(groups.findAllByOrderByNameAsc().stream()
                .filter(g -> !"Test group".equals(g.getName()))
                .toList());
    }

    private String rawKeyValue() {
        return jdbc.queryForObject("select key_value from api_key where id = ?", String.class, keyId);
    }

    private String rawIntegrationSecret() {
        return jdbc.queryForObject("select client_secret from service_integration where id = ?",
                String.class, integrationId);
    }

    @Test
    void valuesStartOnTheUnnamedKeyWithNothingOnDiskChanged() {
        // the ciphertext is the original format - no id, nothing to migrate
        assertThat(rawKeyValue()).doesNotContain("kp1:");
        assertThat(EncryptionService.keyIdOf(rawKeyValue())).isEqualTo(EncryptionService.LEGACY_KEY_ID);
        assertThat(serviceWith(null, null).countByKeyId())
                .containsEntry(EncryptionService.LEGACY_KEY_ID, 2);
    }

    @Test
    void rotatingRewritesEverySecretOntoTheNewKey() {
        String newKey = freshKey();
        ReencryptService rotating = serviceWith(Map.of("2026a", newKey), "2026a");

        ReencryptService.Result result = rotating.reencryptAll("test");

        assertThat(result.rewritten()).isEqualTo(2);
        assertThat(result.examined()).isEqualTo(2);
        assertThat(result.isComplete()).isTrue();
        assertThat(rawKeyValue()).startsWith("kp1:2026a:");
        assertThat(rawIntegrationSecret()).startsWith("kp1:2026a:");
        assertThat(rotating.countByKeyId()).containsExactly(Map.entry("2026a", 2));
    }

    @Test
    void theSecretsStillReadBackCorrectlyAfterwards() {
        String newKey = freshKey();
        serviceWith(Map.of("2026a", newKey), "2026a").reencryptAll("test");

        // the whole point: and readable with the NEW key alone, so the old one
        // can now be taken out of configuration
        EncryptionService newKeyOnly = new EncryptionService(
                new EncryptionProperties(null, Map.of("2026a", newKey), "2026a"));

        assertThat(newKeyOnly.decrypt(rawKeyValue())).isEqualTo("sk_live_fake0123456789");
        assertThat(newKeyOnly.decrypt(rawIntegrationSecret())).isEqualTo("provider-credential");
    }

    @Test
    void theCiphertextActuallyChangedRatherThanJustThePrefix() {
        String before = rawKeyValue();

        serviceWith(Map.of("2026a", freshKey()), "2026a").reencryptAll("test");

        String after = rawKeyValue();
        assertThat(after).isNotEqualTo(before);
        assertThat(after.substring("kp1:2026a:".length())).isNotEqualTo(before);
    }

    @Test
    void runningItTwiceIsHarmlessAndRewritesNothingTheSecondTime() {
        ReencryptService rotating = serviceWith(Map.of("2026a", freshKey()), "2026a");
        rotating.reencryptAll("test");
        String afterFirst = rawKeyValue();

        ReencryptService.Result second = rotating.reencryptAll("test");

        assertThat(second.rewritten()).isZero();
        assertThat(second.examined()).isEqualTo(2);
        assertThat(rawKeyValue()).isEqualTo(afterFirst);
    }

    @Test
    void aValueWhoseKeyIsMissingStopsTheRunAndChangesNothing() {
        // rotate once, then try to rotate again without the key that wrote them
        serviceWith(Map.of("2026a", freshKey()), "2026a").reencryptAll("test");
        String afterFirst = rawKeyValue();

        ReencryptService withoutOldKey = serviceWith(Map.of("2026b", freshKey()), "2026b");

        assertThatThrownBy(() -> withoutOldKey.reencryptAll("test"))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("Nothing was changed");

        // all or nothing: the rows are exactly as they were
        assertThat(rawKeyValue()).isEqualTo(afterFirst);
    }

    @Test
    void rotationLeavesTheKeysOwnMetadataAlone() {
        // read as columns, not entities: after rotation the running context no
        // longer holds the key these rows are encrypted with
        Map<String, Object> before = jdbc.queryForMap(
                "select name, updated_at, expires_on from api_key where id = ?", keyId);

        serviceWith(Map.of("2026a", freshKey()), "2026a").reencryptAll("test");

        Map<String, Object> after = jdbc.queryForMap(
                "select name, updated_at, expires_on from api_key where id = ?", keyId);
        // re-encryption is not an edit: nothing should look touched by it, or
        // every key in the portal appears edited on the morning of a rotation
        assertThat(after).isEqualTo(before);
    }
}
