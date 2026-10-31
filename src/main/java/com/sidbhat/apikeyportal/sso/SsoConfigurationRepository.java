package com.sidbhat.apikeyportal.sso;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SsoConfigurationRepository extends JpaRepository<SsoConfiguration, Long> {

    /** There is at most one connection; this is how everything reads it. */
    Optional<SsoConfiguration> findFirstByOrderByIdAsc();
}
