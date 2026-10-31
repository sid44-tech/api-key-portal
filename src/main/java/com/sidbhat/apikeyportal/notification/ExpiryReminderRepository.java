package com.sidbhat.apikeyportal.notification;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpiryReminderRepository extends JpaRepository<ExpiryReminder, Long> {

    boolean existsByApiKeyIdAndExpiresOnAndThresholdDays(Long apiKeyId, LocalDate expiresOn, int thresholdDays);
}
