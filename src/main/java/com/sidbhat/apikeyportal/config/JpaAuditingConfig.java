package com.sidbhat.apikeyportal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Kept separate from the application class so that web slice tests
 * don't try to bootstrap JPA auditing.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
