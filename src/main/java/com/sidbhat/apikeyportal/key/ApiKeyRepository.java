package com.sidbhat.apikeyportal.key;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long>, JpaSpecificationExecutor<ApiKey> {

    @Query("select distinct k.serviceName from ApiKey k order by k.serviceName")
    List<String> findDistinctServiceNames();

}
