package com.example.hms.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup log names the per-instance login lockout, and only when it is one. */
class StartupSubsystemLoggerLoginThrottleTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(StartupSubsystemLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private boolean saysTheLockoutIsPerInstance() {
        return appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .anyMatch(line -> line.contains("REDIS login lockout") && line.contains("per instance"));
    }

    @Test
    @DisplayName("without Redis the startup log says the lockout is per instance")
    void fallbackIsAnnounced() {
        new StartupSubsystemLogger(new MockEnvironment()).announceDisabledSubsystems();

        assertThat(saysTheLockoutIsPerInstance()).isTrue();
    }

    @Test
    @DisplayName("with Redis wired nothing is said about the lockout")
    void sharedStoreIsQuiet() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.redis.token-blacklist.enabled", "true");

        new StartupSubsystemLogger(env).announceDisabledSubsystems();

        assertThat(saysTheLockoutIsPerInstance()).isFalse();
    }
}
