package com.example.hms.config;

import com.example.hms.service.webhook.WebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the webhook properties (Tier 2 item 45) — the MLLP idiom. */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookAutoConfiguration {
}
