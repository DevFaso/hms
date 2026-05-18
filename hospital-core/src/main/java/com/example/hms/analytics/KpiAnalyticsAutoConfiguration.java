package com.example.hms.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers KPI dashboard configuration properties (row 32 follow-on).
 *
 * <p>Standalone {@code @Configuration} so the existing
 * {@code KpiDashboardService} chain (service + controller + DTOs) does
 * not need to grow new {@code @EnableConfigurationProperties} on every
 * touch. The class is intentionally a thin holder — the matview path
 * read-flag and the refresher-on-flag both live on
 * {@link KpiMaterializedViewProperties}.
 */
@Configuration
@EnableConfigurationProperties(KpiMaterializedViewProperties.class)
public class KpiAnalyticsAutoConfiguration {
}
