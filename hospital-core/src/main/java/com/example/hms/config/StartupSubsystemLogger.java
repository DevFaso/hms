package com.example.hms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs a one-line INFO at startup for each optional integration subsystem
 * that is currently disabled. Without this, a fresh dev or staging
 * environment can run for weeks before someone notices that emails were
 * never sent, the token blacklist is in-memory only (so logout doesn't
 * propagate across instances), or Kafka events are silently dropped.
 *
 * <p>Triggered on {@link ApplicationReadyEvent} so the lines land at the
 * very end of the startup log where they're easy to spot.
 */
@Component
public class StartupSubsystemLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupSubsystemLogger.class);

    private final Environment env;

    public StartupSubsystemLogger(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announceDisabledSubsystems() {
        if (isEmpty(env.getProperty("spring.mail.username"))) {
            log.info(
                    "Subsystem disabled: outbound MAIL — set spring.mail.username + spring.mail.password "
                            + "(activation, password reset, notification emails will not be delivered)");
        }
        if (!isTrue(env.getProperty("app.redis.token-blacklist.enabled"))) {
            log.info(
                    "Subsystem disabled: REDIS token blacklist — using in-memory store. "
                            + "OK for single-instance dev; in multi-instance deployments revoked tokens will "
                            + "remain valid on other instances. Set app.redis.token-blacklist.enabled=true.");
        }
        boolean springKafka = isTrue(env.getProperty("spring.kafka.enabled"));
        boolean appKafka = isTrue(env.getProperty("app.kafka.enabled"));
        if (!springKafka && !appKafka) {
            log.info(
                    "Subsystem disabled: KAFKA event streaming — no broker configured. "
                            + "Chat / EMPI / patient-movement / platform-registry events will not be published. "
                            + "Set spring.kafka.enabled=true and app.kafka.enabled=true to enable.");
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isTrue(String s) {
        return "true".equalsIgnoreCase(s);
    }
}
