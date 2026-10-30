package com.sidbhat.apikeyportal.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sidbhat.apikeyportal.config.JpaAuditingConfig;
import com.sidbhat.apikeyportal.crypto.EncryptionProperties;
import com.sidbhat.apikeyportal.crypto.EncryptionService;

@DataJpaTest
@Import({EncryptionService.class, JpaAuditingConfig.class})
@EnableConfigurationProperties(EncryptionProperties.class)
class ServiceIntegrationRepositoryTest {

    @Autowired
    private ServiceIntegrationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesIntegrationAndStampsAuditTimestamps() {
        ServiceIntegration saved = repository.save(new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD",
                "tenant-1", "client-1", "fake-portal-secret"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getAddedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getLastVerifiedAt()).isNull();
    }

    @Test
    void storesClientSecretEncrypted() {
        ServiceIntegration saved = repository.saveAndFlush(new ServiceIntegration(ServiceProvider.AZURE_AD,
                "Azure AD", "tenant-1", "client-1", "fake-portal-secret"));

        String raw = jdbcTemplate.queryForObject("select client_secret from service_integration where id = ?",
                String.class, saved.getId());

        assertThat(raw).isNotBlank().doesNotContain("fake-portal-secret");
        assertThat(repository.findById(saved.getId()))
                .hasValueSatisfying(i -> assertThat(i.getClientSecret()).isEqualTo("fake-portal-secret"));
    }

    @Test
    void listsIntegrationsSortedByName() {
        repository.save(new ServiceIntegration(ServiceProvider.AZURE_AD, "Work tenant", "t1", "c1", "s1"));
        repository.save(new ServiceIntegration(ServiceProvider.AZURE_AD, "Azure AD", "t2", "c2", "s2"));

        assertThat(repository.findAllByOrderByNameAsc())
                .extracting(ServiceIntegration::getName)
                .containsExactly("Azure AD", "Work tenant");
    }
}
