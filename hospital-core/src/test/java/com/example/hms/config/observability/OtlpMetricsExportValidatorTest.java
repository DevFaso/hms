package com.example.hms.config.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flag being on is not proof that anything is exported: on Boot 4 the auto-configuration
 * backed off silently. The validator must say so at startup.
 */
class OtlpMetricsExportValidatorTest {

    private static final String URL = "https://otlp-gateway.example.net/otlp/v1/metrics";

    private static List<ILoggingEvent> errorsFrom(boolean enabled, OtlpMeterRegistry registry) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (registry != null) {
            beans.addBean("otlpMeterRegistry", registry);
        }
        OtlpMetricsExportValidator validator = new OtlpMetricsExportValidator(
            enabled, URL, "Basic abc", beans.getBeanProvider(OtlpMeterRegistry.class));

        Logger logger = (Logger) LoggerFactory.getLogger(OtlpMetricsExportValidator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            validator.errorWhenEnabledButNoRegistry();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    @Test
    @DisplayName("enabled but no OtlpMeterRegistry bean: one ERROR naming the missing module")
    void enabledWithoutRegistryIsAnError() {
        List<ILoggingEvent> errors = errorsFrom(true, null);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage())
            .contains("no OtlpMeterRegistry")
            .contains("spring-boot-opentelemetry");
    }

    @Test
    @DisplayName("enabled with a registry, or disabled: silent")
    void registryPresentOrDisabledIsSilent() {
        OtlpMeterRegistry registry = new OtlpMeterRegistry(OtlpConfig.DEFAULT, Clock.SYSTEM);
        try {
            assertThat(errorsFrom(true, registry)).isEmpty();
        } finally {
            registry.close();
        }
        assertThat(errorsFrom(false, null)).isEmpty();
    }
}
