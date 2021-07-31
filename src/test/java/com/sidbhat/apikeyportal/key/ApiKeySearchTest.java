package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;

import com.sidbhat.apikeyportal.config.JpaAuditingConfig;
import com.sidbhat.apikeyportal.crypto.EncryptionProperties;
import com.sidbhat.apikeyportal.crypto.EncryptionService;

@DataJpaTest
@Import({EncryptionService.class, JpaAuditingConfig.class, ApiKeyService.class})
@EnableConfigurationProperties(EncryptionProperties.class)
class ApiKeySearchTest {

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        LocalDate today = LocalDate.now();
        repository.save(new ApiKey("Prod secret key", "Stripe", "sk_1", today.minusDays(3), today.plusDays(5)));
        repository.save(new ApiKey("Personal", "GitHub", "ghp_2", today.minusDays(1), null));
        repository.save(new ApiKey("CI deploy", "GitHub", "ghp_3", today.minusDays(10), today.plusDays(90)));
        repository.save(new ApiKey("Deploy", "Heroku", "hk_4", today.minusDays(2), today.plusDays(1)));
    }

    @Test
    void returnsEverythingWithoutFilters() {
        assertThat(service.search(null, "", null, 0).getContent()).hasSize(4);
    }

    @Test
    void matchesNameOrServiceCaseInsensitively() {
        assertThat(service.search("DEPLOY", null, null, 0).getContent()).extracting(ApiKey::getName)
                .containsExactly("CI deploy", "Deploy");
        assertThat(service.search("git", null, null, 0).getContent()).extracting(ApiKey::getName)
                .containsExactly("CI deploy", "Personal");
    }

    @Test
    void filtersByService() {
        assertThat(service.search(null, "GitHub", null, 0).getContent())
                .extracting(ApiKey::getServiceName).containsOnly("GitHub");
    }

    @Test
    void combinesTextAndServiceFilters() {
        assertThat(service.search("deploy", "Heroku", null, 0).getContent())
                .extracting(ApiKey::getName).containsExactly("Deploy");
    }

    @Test
    void sortsByExpiryWithNonExpiringKeysLast() {
        assertThat(service.search(null, null, ApiKeySort.EXPIRES, 0).getContent()).extracting(ApiKey::getName)
                .containsExactly("Deploy", "Prod secret key", "CI deploy", "Personal");
    }

    @Test
    void sortsByNewestFirst() {
        assertThat(service.search(null, null, ApiKeySort.CREATED, 0).getContent()).extracting(ApiKey::getName)
                .containsExactly("Personal", "Deploy", "Prod secret key", "CI deploy");
    }

    @Test
    void pagesResults() {
        for (int i = 0; i < ApiKeyService.PAGE_SIZE; i++) {
            repository.save(new ApiKey("Key " + i, "Zapier", "zp_" + i, LocalDate.now(), null));
        }

        Page<ApiKey> first = service.search(null, null, ApiKeySort.SERVICE, 0);
        Page<ApiKey> second = service.search(null, null, ApiKeySort.SERVICE, 1);

        assertThat(first.getTotalElements()).isEqualTo(ApiKeyService.PAGE_SIZE + 4);
        assertThat(first.getContent()).hasSize(ApiKeyService.PAGE_SIZE);
        assertThat(second.getContent()).hasSize(4).extracting(ApiKey::getServiceName).containsOnly("Zapier");
    }
}
