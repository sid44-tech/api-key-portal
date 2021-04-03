package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sidbhat.apikeyportal.crypto.EncryptionProperties;
import com.sidbhat.apikeyportal.crypto.EncryptionService;

@DataJpaTest
@Import(EncryptionService.class)
@EnableConfigurationProperties(EncryptionProperties.class)
class ApiKeyRepositoryTest {

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesKeyAndStampsAddedAt() {
        ApiKey saved = repository.save(new ApiKey("Deploy token", "GitHub", "ghp_abc123",
                LocalDate.of(2021, 1, 4), LocalDate.of(2021, 7, 4)));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAddedAt()).isNotNull();
    }

    @Test
    void listsKeysOrderedByServiceThenName() {
        repository.save(new ApiKey("Prod", "Stripe", "sk_1", LocalDate.now(), null));
        repository.save(new ApiKey("Personal", "GitHub", "ghp_2", LocalDate.now(), null));
        repository.save(new ApiKey("CI", "GitHub", "ghp_3", LocalDate.now(), null));

        List<ApiKey> keys = repository.findAllByOrderByServiceNameAscNameAsc();

        assertThat(keys).extracting(ApiKey::getName).containsExactly("CI", "Personal", "Prod");
    }

    @Test
    void storesKeyValueEncrypted() {
        ApiKey saved = repository.save(new ApiKey("Prod", "Stripe", "sk_live_secret", LocalDate.now(), null));
        entityManager.flush();
        entityManager.clear();

        String raw = jdbcTemplate.queryForObject("select key_value from api_key where id = ?", String.class,
                saved.getId());

        assertThat(raw).isNotBlank().doesNotContain("sk_live_secret");
        assertThat(repository.findById(saved.getId()))
                .hasValueSatisfying(key -> assertThat(key.getKeyValue()).isEqualTo("sk_live_secret"));
    }

    @Test
    void findsDistinctServiceNames() {
        repository.save(new ApiKey("Prod", "Stripe", "sk_1", LocalDate.now(), null));
        repository.save(new ApiKey("Personal", "GitHub", "ghp_2", LocalDate.now(), null));
        repository.save(new ApiKey("CI", "GitHub", "ghp_3", LocalDate.now(), null));

        assertThat(repository.findDistinctServiceNames()).containsExactly("GitHub", "Stripe");
    }
}
