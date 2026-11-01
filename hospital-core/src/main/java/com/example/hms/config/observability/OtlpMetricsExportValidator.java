package com.example.hms.config.observability;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Logs loudly at startup when the Micrometer OTLP exporter is switched on but not pointed
 * anywhere a collector is listening.
 *
 * <p>{@code OTEL_METRICS_ENABLED} and {@code OTEL_METRICS_EXPORT_URL} are two separate Railway
 * variables and nothing stops an operator setting the first without the second. The result is
 * otherwise completely silent: Boot builds the registry against its own {@code localhost:4318}
 * default, every 60-second push fails against nothing, and Micrometer swallows the failure. The
 * dashboards simply stay empty while everything reports healthy.
 *
 * <p><strong>This deliberately does not fail the context</strong>, unlike {@link
 * SplunkLoggingProperties#validateWhenEnabled()}, which it otherwise resembles. Splunk's appender
 * is opt-in log shipping; this is a telemetry exporter on a clinical system's startup path, and a
 * misconfigured <em>metrics</em> endpoint must never be the reason a hospital backend refuses to
 * boot — that converts a missing graph into an outage, and into a deploy that cannot be rolled
 * forward. An ERROR on the startup path, naming the variable and the fix, is the proportionate
 * response: visible to anyone reading the deploy log, harmless to patient care.
 */
@Component
public class OtlpMetricsExportValidator {

    private static final Logger log = LoggerFactory.getLogger(OtlpMetricsExportValidator.class);

    /** Micrometer posts to the metrics signal URL, not the OTLP gateway base. */
    private static final String SIGNAL_PATH = "/v1/metrics";

    private final boolean enabled;
    private final String url;
    private final String authorization;

    public OtlpMetricsExportValidator(
            @Value("${management.otlp.metrics.export.enabled:false}") boolean enabled,
            @Value("${management.otlp.metrics.export.url:}") String url,
            @Value("${management.otlp.metrics.export.headers.Authorization:}") String authorization) {
        this.enabled = enabled;
        this.url = url;
        this.authorization = authorization;
    }

    @PostConstruct
    void warnWhenExportCannotWork() {
        if (!enabled) {
            return;
        }
        if (url == null || url.isBlank()) {
            log.error("OTEL_METRICS_ENABLED=true but no OTLP metrics URL is configured. "
                + "No metric will reach Grafana Cloud, and the export failure is swallowed. "
                + "Set OTEL_METRICS_EXPORT_URL, or set OTEL_METRICS_ENABLED=false. "
                + "See docs/GRAFANA_CLOUD_SETUP.md.");
            return;
        }
        if (isLoopback(url)) {
            log.error("OTEL_METRICS_ENABLED=true but the OTLP metrics URL is a loopback address "
                + "({}). If no local collector is listening there, no metric reaches Grafana "
                + "Cloud and the failure is swallowed. Expected the gateway's metrics endpoint, "
                + "which ends in /v1/metrics — the bare /otlp base is the agent's endpoint, not "
                + "Micrometer's. Set OTEL_METRICS_EXPORT_URL via "
                + "scripts/grafana-cloud-setup.sh. Ignore this if you are running a collector "
                + "locally on purpose.", url);
        } else if (!url.endsWith(SIGNAL_PATH)) {
            // The specific mistake the loopback message describes but could not detect: the
            // agent's variable holds the gateway BASE, Micrometer's wants the metrics signal
            // URL. Copying the base here yields a rejected POST every 60s, and nothing says so.
            log.error("The OTLP metrics URL does not end in {} ({}). Micrometer needs the "
                + "metrics signal URL, not the gateway base that OTEL_EXPORTER_OTLP_ENDPOINT "
                + "holds for the agent — pushing to the base is rejected, and the failure is "
                + "swallowed. Append {} unless a non-standard collector route is intended.",
                SIGNAL_PATH, url, SIGNAL_PATH);
        }

        if (authorization == null || authorization.isBlank()) {
            log.error("OTEL_METRICS_ENABLED=true and an OTLP metrics URL is set, but no "
                + "Authorization header is configured. Grafana Cloud answers 401 to every "
                + "push and Micrometer swallows the failure, so the dashboards stay empty "
                + "with no error. Set GRAFANA_OTLP_AUTH_HEADER — note it is a SEPARATE "
                + "Railway variable from OTEL_EXPORTER_OTLP_HEADERS, which the agent reads. "
                + "scripts/grafana-cloud-setup.sh sets both from one token.");
        }
    }

    /**
     * True for a loopback host on either OTLP port, over http or https.
     *
     * <p>Matching the host rather than one exact string: the default that triggers this is
     * {@code http://localhost:4318/v1/metrics}, but {@code 127.0.0.1}, {@code [::1]} and the gRPC
     * port 4317 are the same mistake wearing a different hat. A local collector is a legitimate
     * setup, which is why this only ever warns.
     */
    private static boolean isLoopback(String candidate) {
        String normalised = candidate.toLowerCase(Locale.ROOT);
        return normalised.startsWith("http://localhost")
            || normalised.startsWith("https://localhost")
            || normalised.startsWith("http://127.0.0.1")
            || normalised.startsWith("https://127.0.0.1")
            || normalised.startsWith("http://[::1]")
            || normalised.startsWith("https://[::1]");
    }
}
