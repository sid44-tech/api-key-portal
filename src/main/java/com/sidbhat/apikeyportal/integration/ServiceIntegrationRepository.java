package com.sidbhat.apikeyportal.integration;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceIntegrationRepository extends JpaRepository<ServiceIntegration, Long> {

    List<ServiceIntegration> findAllByOrderByNameAsc();
}
