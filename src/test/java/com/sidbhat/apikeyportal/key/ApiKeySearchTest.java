package com.sidbhat.apikeyportal.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.sidbhat.apikeyportal.crypto.EncryptionProperties;
import com.sidbhat.apikeyportal.crypto.EncryptionService;

@DataJpaTest
@Import({EncryptionService.class, ApiKeyService.class})
@EnableConfigurationProperties(EncryptionProperties.class)
class ApiKeySearchTest {

    @Autowired
    private ApiKeyRepository repository;

    @Autowired
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repository.save(new ApiKey("Prod secret key", "Stripe", "sk_1", LocalDate.now(), null));
        repository.save(new ApiKey("Personal", "GitHub", "ghp_2", LocalDate.now(), null));
        repository.save(new ApiKey("CI deploy", "GitHub", "ghp_3", LocalDate.now(), null));
        repository.save(new ApiKey("Deploy", "Heroku", "hk_4", LocalDate.now(), null));
    }

    @Test
    void returnsEverythingWithoutFilters() {
        assertThat(service.search(null, "")).hasSize(4);
    }

    @Test
    void matchesNameOrServiceCaseInsensitively() {
        assertThat(service.search("DEPLOY", null)).extracting(ApiKey::getName)
                .containsExactly("CI deploy", "Deploy");
        assertThat(service.search("git", null)).extracting(ApiKey::getName)
                .containsExactly("CI deploy", "Personal");
    }

    @Test
    void filtersByService() {
        assertThat(service.search(null, "GitHub")).extracting(ApiKey::getServiceName).containsOnly("GitHub");
    }

    @Test
    void combinesTextAndServiceFilters() {
        assertThat(service.search("deploy", "Heroku")).extracting(ApiKey::getName).containsExactly("Deploy");
    }
}
