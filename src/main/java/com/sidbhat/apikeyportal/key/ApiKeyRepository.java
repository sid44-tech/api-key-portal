package com.sidbhat.apikeyportal.key;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    List<ApiKey> findAllByOrderByServiceNameAscNameAsc();

}
