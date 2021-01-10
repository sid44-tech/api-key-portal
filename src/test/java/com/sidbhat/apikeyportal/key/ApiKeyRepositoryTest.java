package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class ApiKeyRepositoryTest {

    @Autowired
    private ApiKeyRepository repository;

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
}
