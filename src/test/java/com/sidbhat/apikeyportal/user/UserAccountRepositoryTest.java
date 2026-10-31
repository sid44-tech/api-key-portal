package com.sidbhat.apikeyportal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

import com.sidbhat.apikeyportal.config.JpaAuditingConfig;
import com.sidbhat.apikeyportal.crypto.EncryptionProperties;
import com.sidbhat.apikeyportal.crypto.EncryptionService;

@DataJpaTest
@Import({JpaAuditingConfig.class, EncryptionService.class})
@EnableConfigurationProperties(EncryptionProperties.class)
class UserAccountRepositoryTest {

    @Autowired
    private UserAccountRepository accounts;

    @Autowired
    private UserGroupRepository groups;

    @Autowired
    private TestEntityManager entityManager;

    private UserGroup platformTeam;

    @BeforeEach
    void setUp() {
        platformTeam = groups.save(new UserGroup("Platform team"));
    }

    private UserAccount save(String email, String name, UserRole role, UserGroup group) {
        return accounts.save(new UserAccount(email, name, "$2a$10$not-a-real-hash", role, group));
    }

    @Test
    void savesAccountAndStampsAuditTimestamps() {
        UserAccount saved = save("alex@example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getLastLoginAt()).isNull();
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void findsAccountsByEmailWhateverTheCasing() {
        save("Alex@Example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam);

        // the entity lower-cases on the way in, and the lookup is case-insensitive too
        assertThat(accounts.findByEmailIgnoreCase("ALEX@EXAMPLE.COM"))
                .hasValueSatisfying(a -> assertThat(a.getDisplayName()).isEqualTo("Alex Carter"));
        assertThat(accounts.existsByEmailIgnoreCase("alex@example.com")).isTrue();
    }

    @Test
    void refusesTwoAccountsWithTheSameEmail() {
        save("alex@example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam);
        entityManager.flush();

        // SQLite reports this as a generic system error, so match on the constraint itself
        assertThatThrownBy(() -> accounts.saveAndFlush(
                new UserAccount("alex@example.com", "Impostor", "$2a$10$x", UserRole.MEMBER, platformTeam)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("idx_user_account_email");
    }

    @Test
    void listsOnlyTheAccountsInOneGroup() {
        UserGroup okafors = groups.save(new UserGroup("The Okafors"));
        save("sam@example.com", "Sam Diaz", UserRole.MEMBER, platformTeam);
        save("alex@example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam);
        save("jo@example.com", "Jo Okafor", UserRole.GROUP_ADMIN, okafors);

        assertThat(accounts.findByGroup_IdOrderByDisplayNameAsc(platformTeam.getId()))
                .extracting(UserAccount::getDisplayName)
                .containsExactly("Alex Carter", "Sam Diaz");
    }

    @Test
    void recordLoginDoesNotTouchUpdatedAt() {
        UserAccount saved = save("alex@example.com", "Alex Carter", UserRole.GROUP_ADMIN, platformTeam);
        entityManager.flush();
        Instant updatedAt = saved.getUpdatedAt();
        Instant loggedInAt = Instant.parse("2026-09-18T12:00:00Z");

        accounts.recordLogin(saved.getId(), loggedInAt);
        entityManager.clear();

        UserAccount reloaded = accounts.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getLastLoginAt()).isEqualTo(loggedInAt);
        // SQLite stores timestamps with millisecond precision, so compare at that granularity
        assertThat(reloaded.getUpdatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(updatedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void refusesTwoGroupsWithNamesThatDifferOnlyInCase() {
        entityManager.flush();
        assertThat(groups.existsByNameIgnoreCase("platform team")).isTrue();

        // the unique index is on lower(name), so casing alone doesn't make a new group
        assertThatThrownBy(() -> groups.saveAndFlush(new UserGroup("PLATFORM TEAM")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("idx_user_group_name");
    }
}
