package com.example.hms.config.observability;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Splunk HEC logging configuration, surfaced as a Spring bean so misconfiguration fails fast at
 * startup rather than silently swallowing log events. The companion {@code logback-spring.xml}
 * reads the same Spring properties via {@code <springProperty>} and wires them into {@link
 * com.example.hms.logging.SplunkHecAppender}.
 *
 * <p>Pattern matches {@code JwtProperties} / {@code FeatureFlagProperties} elsewhere in this
 * package — env-driven, validated, scanned by {@code @ConfigurationPropertiesScan} on the
 * application class.
 *
 * <p>Per the HMS coverage rules in {@code build.gradle}, classes under {@code config/**} are
 * excluded from JaCoCo verification (they are mostly inert wiring). Tests still exist alongside
 * for behavioural correctness — they don't show up in the coverage gate.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.observability.splunk")
public class SplunkLoggingProperties {

    /** When {@code false} the appender silently no-ops; left as the default for local dev. */
    private boolean enabled = false;

    /** Nested HEC config — kept as an inner type so {@code @Validated} cascades cleanly. */
    private final Hec hec = new Hec();

    /**
     * Fails the application context if {@code enabled=true} but the URL or token are blank.
     * Cheaper to fail at boot than to discover at 02:00 that no logs reached SIEM.
     */
    @PostConstruct
    void validateWhenEnabled() {
        if (!enabled) {
            return;
        }
        if (isBlank(hec.getUrl())) {
            throw new IllegalStateException(
                "app.observability.splunk.enabled=true but app.observability.splunk.hec.url is blank — "
                    + "set SPLUNK_HEC_URL or set SPLUNK_HEC_ENABLED=false.");
        }
        if (isBlank(hec.getToken())) {
            throw new IllegalStateException(
                "app.observability.splunk.enabled=true but app.observability.splunk.hec.token is blank — "
                    + "set SPLUNK_HEC_TOKEN (env var) or set SPLUNK_HEC_ENABLED=false.");
        }
        if (!hec.getUrl().toLowerCase().startsWith("https://")) {
            throw new IllegalStateException(
                "app.observability.splunk.hec.url must be HTTPS in any non-local environment — "
                    + "got: " + hec.getUrl());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** HEC connection details. */
    @Getter
    @Setter
    public static class Hec {

        /** HEC base URL, e.g. {@code https://splunk-hec.example.com:8088}. */
        @NotBlank
        private String url = "";

        /** HEC token (env-driven; never logged). */
        @NotBlank
        private String token = "";

        /** Splunk index — must match an index the token has write permission for. */
        private String index = "main";

        /** Splunk source field — used in dashboard filters. */
        private String source = "hms-backend";

        /** Splunk sourcetype — drives field extraction in Splunk. */
        private String sourceType = "spring-boot:json";

        /** Splunk host field — defaults to the container hostname. */
        private String host = "";
    }
}
