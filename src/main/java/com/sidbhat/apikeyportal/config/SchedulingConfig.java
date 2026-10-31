package com.sidbhat.apikeyportal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}; the one job today is the expiry reminder sweep. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
