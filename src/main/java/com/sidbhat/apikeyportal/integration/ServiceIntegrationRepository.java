package com.sidbhat.apikeyportal.integration;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sidbhat.apikeyportal.user.UserGroup;

public interface ServiceIntegrationRepository extends JpaRepository<ServiceIntegration, Long> {

    List<ServiceIntegration> findAllByOrderByNameAsc();

    List<ServiceIntegration> findByGroup_IdOrderByNameAsc(Long groupId);

    /** How many integrations each group holds, as {@code [groupId, count]} rows. */
    @Query("select i.group.id, count(i) from ServiceIntegration i where i.group is not null group by i.group.id")
    List<Object[]> countByGroup();

    long countByGroup_Id(Long groupId);

    /**
     * Hands integrations that predate accounts to the bootstrap admin's group. Only
     * ever matches rows on the first start after the multi-user upgrade.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ServiceIntegration i set i.group = :group where i.group is null")
    int claimUngrouped(@Param("group") UserGroup group);
}
