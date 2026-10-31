package com.sidbhat.apikeyportal.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, Long> {

    Page<ActivityEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<ActivityEvent> findByGroupIdOrderByOccurredAtDesc(Long groupId, Pageable pageable);
}
