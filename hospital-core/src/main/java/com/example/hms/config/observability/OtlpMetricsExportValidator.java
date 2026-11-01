package com.example.hms.config.observability;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails the context at boot when the Micrometer OTLP exporter is switched on but not actually
 * pointed anywhere useful.
 *
 * <p>{@code OTEL_METRICS_ENABLED} and {@code OTEL_METRICS_EXPORT_URL} are two separate Railway
 * variables, and nothing stops an operator setting the first without the second. The result is
 * silent: Boot builds the registry against its own {@code localhost:4318} default, every 60-second
 * push fails against nothing, and Micrometer swallows the failure as a debug-level warning. The
 * dashboards simply stay empty, which is the hardest kind of broken to diagnose — everything
 * reports healthy.
 *
 * <p>Same reasoning and shape as {@link SplunkLoggingProperties#validateWhenEnabled()}: cheaper to
 * refuse to boot than to discover at 02:00 that no metric ever arrived. The check is deliberately
 * narrow — it only fires when export is explicitly enabled, so the default-off path and every test
 * profile are untouched.
 */
@Component
public class OtlpMetricsExportValidator {

    /** Boot's own default; a loopback target is never right for a deployed service. */
    private static final String BOOT_DEFAULT_URL = "http://localhost:4318/v1/metrics";

    private final boolean enabled;
    private final String url;

    public OtlpMetricsExportValidator(
            @Value("${management.otlp.metrics.export.enabled:false}") boolean enabled,
            @Value("${management.otlp.metrics.export.url:}") String url) {
        this.enabled = enabled;
        this.url = url;
    }

    @PostConstruct
    void validateWhenEnabled() {
        if (!enabled) {
            return;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "OTEL_METRICS_ENABLED=true but no OTLP metrics URL is configured — "
                    + "set OTEL_METRICS_EXPORT_URL to the Grafana Cloud gateway's metrics "
                    + "endpoint (note the /v1/metrics suffix; the bare /otlp base is the "
                    + "agent's endpoint, not Micrometer's), or set OTEL_METRICS_ENABLED=false. "
                    + "See docs/GRAFANA_CLOUD_SETUP.md.");
        }
        if (isLoopback(url)) {
            throw new IllegalStateException(
                "OTEL_METRICS_ENABLED=true but the OTLP metrics URL is still the loopback "
                    + "default (" + url + "). Nothing is listening there in a deployed "
                    + "environment, and Micrometer swallows the export failure, so the "
                    + "dashboards would stay empty with no error. Set OTEL_METRICS_EXPORT_URL "
                    + "via scripts/grafana-cloud-setup.sh, or set OTEL_METRICS_ENABLED=false.");
        }
    }

    /**
     * True for the loopback default. Only meaningful alongside {@code enabled}: running the local
     * compose stack against a real local collector is legitimate, which is why this is reached
     * only when something explicitly turned export on without supplying a destination.
     */
    private static boolean isLoopback(String candidate) {
        String normalised = candidate.toLowerCase(Locale.ROOT);
        return normalised.equals(BOOT_DEFAULT_URL)
            || normalised.startsWith("http://localhost:4318")
            || normalised.startsWith("http://127.0.0.1:4318");
    }
}
