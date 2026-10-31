package com.sidbhat.apikeyportal.key;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserGroup;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long>, JpaSpecificationExecutor<ApiKey> {

    @Query("select distinct k.serviceName from ApiKey k where k.group.id = :groupId order by k.serviceName")
    List<String> findDistinctServiceNamesInGroup(@Param("groupId") Long groupId);

    @Query("select distinct k.serviceName from ApiKey k order by k.serviceName")
    List<String> findDistinctServiceNames();

    /**
     * Bulk update rather than dirty checking so that revealing a key doesn't bump updated_at.
     */
    @Modifying(flushAutomatically = true)
    @Query("update ApiKey k set k.lastRevealedAt = :revealedAt where k.id = :id")
    int markRevealed(@Param("id") Long id, @Param("revealedAt") Instant revealedAt);

    /** How many keys each of the given people holds, as {@code [ownerId, count]} rows. */
    @Query("select k.owner.id, count(k) from ApiKey k where k.owner.id in :ownerIds group by k.owner.id")
    List<Object[]> countByOwnerIds(@Param("ownerIds") List<Long> ownerIds);

    /** How many keys each group holds, as {@code [groupId, count]} rows. */
    @Query("select k.group.id, count(k) from ApiKey k where k.group is not null group by k.group.id")
    List<Object[]> countByGroup();

    long countByGroup_Id(Long groupId);

    /**
     * Keys expiring in the window that somebody still wants to hear about. Keys
     * with no owner can't be emailed, and a disabled owner shouldn't be.
     */
    @Query("select k from ApiKey k where k.expiresOn between :from and :to "
            + "and k.expiryNotificationsMuted = false and k.owner is not null and k.owner.enabled = true "
            + "order by k.expiresOn, k.id")
    List<ApiKey> findExpiringBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Hands keys that predate accounts to the bootstrap admin. Only ever matches
     * rows on the first start after the multi-user upgrade.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApiKey k set k.owner = :owner, k.group = :group where k.owner is null")
    int claimOwnerless(@Param("owner") UserAccount owner, @Param("group") UserGroup group);

}
