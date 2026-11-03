package com.sidbhat.apikeyportal.alerts;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertChannelRepository extends JpaRepository<AlertChannel, Long> {

    /** One channel per group per provider, by the unique index. */
    Optional<AlertChannel> findByGroup_IdAndProvider(Long groupId, ChannelProvider provider);

    List<AlertChannel> findByGroup_IdOrderByProvider(Long groupId);

    List<AlertChannel> findByEnabledTrueAndNotifyLockoutsTrue();

    boolean existsByEnabledTrueAndNotifyExpiriesTrue();
}
