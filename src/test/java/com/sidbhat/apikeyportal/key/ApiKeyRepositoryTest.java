package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sidbhat.apikeyportal.config.JpaAuditingConfig;
import com.sidbhat.apikeyportal.crypto.EncryptionProperties;
import com.sidbhat.apikeyportal.crypto.EncryptionService;

@DataJpaTest
@Import({EncryptionService.class, JpaAuditingConfig.class})
@EnableConfigurationProperties(EncryptionProperties.class)
class ApiKeyRepositoryTest {

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesKeyAndStampsAuditTimestamps() {
        ApiKey saved = repository.save(new ApiKey("Deploy token", "GitHub", "ghp_abc123",
                LocalDate.of(2021, 1, 4), LocalDate.of(2021, 7, 4)));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAddedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void markRevealedDoesNotTouchUpdatedAt() {
        ApiKey saved = repository.save(new ApiKey("Prod", "Stripe", "sk_1", LocalDate.now(), null));
        entityManager.flush();
        Instant updatedAt = saved.getUpdatedAt();
        Instant revealedAt = Instant.parse("2021-09-18T12:00:00Z");

        repository.markRevealed(saved.getId(), revealedAt);
        entityManager.clear();

        ApiKey reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getLastRevealedAt()).isEqualTo(revealedAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void storesKeyValueEncrypted() {
        ApiKey saved = repository.save(new ApiKey("Prod", "Stripe", "fake-secret-value", LocalDate.now(), null));
        entityManager.flush();
        entityManager.clear();

        String raw = jdbcTemplate.queryForObject("select key_value from api_key where id = ?", String.class,
                saved.getId());

        assertThat(raw).isNotBlank().doesNotContain("fake-secret-value");
        assertThat(repository.findById(saved.getId()))
                .hasValueSatisfying(key -> assertThat(key.getKeyValue()).isEqualTo("fake-secret-value"));
    }

    @Test
    void findsDistinctServiceNames() {
        repository.save(new ApiKey("Prod", "Stripe", "sk_1", LocalDate.now(), null));
        repository.save(new ApiKey("Personal", "GitHub", "ghp_2", LocalDate.now(), null));
        repository.save(new ApiKey("CI", "GitHub", "ghp_3", LocalDate.now(), null));

        assertThat(repository.findDistinctServiceNames()).containsExactly("GitHub", "Stripe");
    }
}
