package com.playpulse.playpulse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled methods (retry promotion and the recovery sweep). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
