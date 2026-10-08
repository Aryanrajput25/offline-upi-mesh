package com.demo.upimesh.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration //It is used when we want to define application-level configuration or beans.
@EnableScheduling //This enables Spring's scheduled-task mechanism. My project has scheduled cleanup logic in the idempotency system.
                  //it supports cleanup of expired idempotency records so the in-memory deduplication map does not grow indefinitely.
public class AppConfig {
}
