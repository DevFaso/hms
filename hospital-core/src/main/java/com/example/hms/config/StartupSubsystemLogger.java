package com.example.hms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs a one-line INFO (or WARN) at startup for each optional integration
 * subsystem that is currently disabled or misconfigured. Without this, a
 * fresh dev or staging environment can run for weeks before someone notices
 * that emails were never sent, the token blacklist is in-memory only (so
 * logout doesn't propagate across instances), or Kafka events are silently
 * dropped.
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
        announceMail();
        announceRedisBlacklist();
        announceKafka();
    }

    /**
     * Mail is "configured" when the host is set AND, if SMTP auth is
     * enabled (the default), both username and password are present.
     * Splitting into three explicit cases makes it obvious which knob
     * is missing instead of the previous opaque "set username and
     * password" message that fired even when the host wasn't set yet.
     */
    // Sonar S2068 false positive: the literal word "password" inside the
    // log messages refers to the Spring property name spring.mail.password,
    // not a hardcoded credential. The actual values are read from
    // Environment via env.getProperty() and never written to logs.
    @SuppressWarnings("java:S2068")
    private void announceMail() {
        String host = env.getProperty("spring.mail.host");
        if (isEmpty(host)) {
            log.info(
                    "Subsystem disabled: outbound MAIL — spring.mail.host not set "
                            + "(activation, password reset, notification emails will not be delivered)");
            return;
        }
        // Default Spring Boot value when the property is unset is true; only
        // an explicit "false" disables auth.
        boolean authEnabled =
                !"false".equalsIgnoreCase(env.getProperty("spring.mail.properties.mail.smtp.auth"));
        if (!authEnabled) {
            log.info(
                    "Subsystem note: outbound MAIL host {} configured WITHOUT SMTP auth "
                            + "(spring.mail.properties.mail.smtp.auth=false) — username/password not required",
                    host);
            return;
        }
        boolean userMissing = isEmpty(env.getProperty("spring.mail.username"));
        boolean passMissing = isEmpty(env.getProperty("spring.mail.password"));
        if (userMissing || passMissing) {
            log.info(
                    "Subsystem disabled: outbound MAIL host {} configured but credentials missing "
                            + "(username={}, password={}). Set spring.mail.username + spring.mail.password "
                            + "or disable SMTP auth.",
                    host,
                    userMissing ? "MISSING" : "set",
                    passMissing ? "MISSING" : "set");
        }
    }

    private void announceRedisBlacklist() {
        if (!isTrue(env.getProperty("app.redis.token-blacklist.enabled"))) {
            log.info(
                    "Subsystem disabled: REDIS token blacklist — using in-memory store. "
                            + "OK for single-instance dev; in multi-instance deployments revoked tokens will "
                            + "remain valid on other instances. Set app.redis.token-blacklist.enabled=true.");
        }
    }

    /**
     * Kafka is gated by two flags that toggle different layers — the
     * {@code spring.kafka.enabled} flag controls whether Kafka beans
     * (consumers, listener containers) are created, and {@code app.kafka.enabled}
     * controls whether the application's domain publishers actually emit.
     * Reporting them independently makes a partial / mismatched setup
     * visible. Mismatch is almost always misconfiguration, so it's WARN.
     */
    private void announceKafka() {
        boolean springKafka = isTrue(env.getProperty("spring.kafka.enabled"));
        boolean appKafka = isTrue(env.getProperty("app.kafka.enabled"));

        if (!springKafka && !appKafka) {
            log.info(
                    "Subsystem disabled: KAFKA fully off (spring.kafka.enabled=false, "
                            + "app.kafka.enabled=false). Chat / EMPI / patient-movement / "
                            + "platform-registry events will not be published.");
            return;
        }
        if (springKafka && !appKafka) {
            log.warn(
                    "Subsystem partially enabled: KAFKA beans are CREATED (spring.kafka.enabled=true) "
                            + "but app-level publishing is OFF (app.kafka.enabled=false). Consumers may run "
                            + "without producers — likely a misconfig.");
            return;
        }
        // Past the two earlier returns, (springKafka || appKafka) AND
        // (!springKafka || appKafka) both hold, which together imply
        // appKafka is true. So the third partial-enable case is just
        // !springKafka — the explicit `&& appKafka` is a tautology Sonar
        // (correctly) flags as always true.
        if (!springKafka) {
            log.warn(
                    "Subsystem partially enabled: app.kafka.enabled=true but Kafka beans are NOT "
                            + "created (spring.kafka.enabled=false). Publishers will fail at runtime — "
                            + "likely a misconfig.");
        }
        // Both true: nothing to log; Kafka is fully enabled.
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isTrue(String s) {
        return "true".equalsIgnoreCase(s);
    }
}
