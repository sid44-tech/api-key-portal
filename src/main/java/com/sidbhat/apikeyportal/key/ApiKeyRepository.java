package com.sidbhat.apikeyportal.key;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long>, JpaSpecificationExecutor<ApiKey> {

    @Query("select distinct k.serviceName from ApiKey k order by k.serviceName")
    List<String> findDistinctServiceNames();

    /**
     * Bulk update rather than dirty checking so that revealing a key doesn't bump updated_at.
     */
    @Modifying(flushAutomatically = true)
    @Query("update ApiKey k set k.lastRevealedAt = :revealedAt where k.id = :id")
    int markRevealed(@Param("id") Long id, @Param("revealedAt") Instant revealedAt);

}
