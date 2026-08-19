package com.example.hms.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logback appender that ships log events to Splunk's HTTP Event Collector (HEC).
 *
 * <p>Designed to live alongside the existing console appender so prod/UAT logs can be consumed by
 * SIEM/SOC dashboards in Splunk Cloud while local dev keeps reading the console as today. The
 * appender is wired in {@code logback-spring.xml} and configured by Spring environment properties
 * (see {@code app.observability.splunk.*} in {@code application.properties}).
 *
 * <p>Why a custom appender rather than {@code splunk-library-javalogging}: Splunk's official
 * library is not published to Maven Central (it ships from GitHub releases), so depending on it
 * makes the build fragile and slows CI. The appender here uses only JDK-built-in {@link
 * HttpClient}, weighs &lt;200 lines, is fully unit-testable with a mock client, and keeps the
 * dependency surface unchanged.
 *
 * <p>Failure behaviour: if the HEC endpoint is unreachable or returns 4xx/5xx, the failure is
 * counted internally and the application continues. We never throw from {@link
 * #append(ILoggingEvent)} so a transient Splunk outage cannot crash a request.
 *
 * <p>Security: the {@code token} is read from the Spring environment (env var) — never hardcoded.
 * The HEC URL must be HTTPS in non-local environments; this is enforced by {@code
 * SplunkLoggingProperties} validation, not here, so the appender stays a single-responsibility
 * I/O writer.
 */
public class SplunkHecAppender extends AppenderBase<ILoggingEvent> {

    /**
     * HEC endpoint suffix. Splunk Cloud / Splunk Enterprise both expose this path
     * and the value is fixed by the Splunk HTTP Event Collector protocol — not
     * an environment-specific URL. The configurable bit (the host) lives in the
     * {@link #url} field which is set from {@code logback-spring.xml} /
     * {@code SplunkLoggingProperties}.
     */
    @SuppressWarnings("java:S1075") // Protocol constant, not an environment URI — see field javadoc.
    static final String HEC_PATH = "/services/collector/event";

    /** Auth header value prefix per Splunk HEC docs. */
    private static final String AUTH_PREFIX = "Splunk ";

    /** Connect+request timeout. HEC under load is rarely &gt;5s; 10s is generous without stalling. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    // ── Configurable from logback-spring.xml ───────────────────────────────────────────────────
    private boolean enabled;
    private String url;
    private String token;
    private String index;
    private String source;
    private String sourceType;
    private String host;
    private String application;
    private String environment;

    /** HttpClient is package-private + setter-injected so tests can supply a mock. */
    private HttpClient httpClient;

    /** Counts of HEC POST failures, exposed for tests + future health-indicator wiring. */
    private final AtomicLong failureCount = new AtomicLong();

    @Override
    public void start() {
        if (!enabled) {
            // Silently no-op: appender is registered unconditionally so the same logback-spring.xml
            // works in dev (no Splunk) and prod (Splunk). enabled=false is the local-dev path.
            super.start();
            return;
        }
        if (isBlank(url) || isBlank(token)) {
            addError(
                "SplunkHecAppender enabled but url/token are blank — refusing to start. "
                    + "Set SPLUNK_HEC_URL and SPLUNK_HEC_TOKEN, or set SPLUNK_HEC_ENABLED=false.");
            return;
        }
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build();
        }
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!enabled || !isStarted()) {
            return;
        }
        try {
            HttpRequest request = buildRequest(event);
            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                failureCount.incrementAndGet();
                // Use addWarn (Logback status manager) — addError would be too noisy for transient
                // 5xx blips and would itself loop if our root logger forwarded to this appender.
                addWarn(
                    "Splunk HEC POST returned status "
                        + response.statusCode()
                        + " — first 200 chars of body: "
                        + truncate(response.body(), 200));
            }
        } catch (IOException | InterruptedException | URISyntaxException ex) {
            failureCount.incrementAndGet();
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            addWarn("Splunk HEC POST failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * Builds the HTTP POST request for a single event. Package-private so the test can verify the
     * URI, headers, and body without sending anything.
     */
    HttpRequest buildRequest(ILoggingEvent event) throws URISyntaxException {
        URI target = new URI(stripTrailingSlash(url) + HEC_PATH);
        String body = renderPayload(event);
        return HttpRequest.newBuilder()
            .uri(target)
            .timeout(DEFAULT_TIMEOUT)
            .header("Authorization", AUTH_PREFIX + token)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    }

    /**
     * Renders an event into a Splunk HEC JSON payload. Public-package so tests can assert the
     * exact wire format. We deliberately handcode JSON instead of pulling in a serializer to keep
     * the appender's classpath isolated from any Jackson upgrade fallout.
     */
    String renderPayload(ILoggingEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("time", event.getTimeStamp() / 1000.0);
        if (!isBlank(host)) envelope.put("host", host);
        if (!isBlank(source)) envelope.put("source", source);
        if (!isBlank(sourceType)) envelope.put("sourcetype", sourceType);
        if (!isBlank(index)) envelope.put("index", index);

        Map<String, Object> eventBody = new LinkedHashMap<>();
        eventBody.put("level", event.getLevel().toString());
        eventBody.put("logger", event.getLoggerName());
        eventBody.put("thread", event.getThreadName());
        eventBody.put("message", event.getFormattedMessage());
        if (!isBlank(application)) eventBody.put("application", application);
        if (!isBlank(environment)) eventBody.put("environment", environment);

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && !mdc.isEmpty()) {
            // Trace IDs from the OTel Java Agent surface in MDC under traceId/spanId — keeping
            // them in the event body lets Splunk correlate to Grafana Tempo traces by ID.
            eventBody.put("mdc", mdc);
        }

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            eventBody.put("exception", throwable.getClassName() + ": " + throwable.getMessage());
            eventBody.put("stacktrace", ThrowableProxyUtil.asString(throwable));
        }

        envelope.put("event", eventBody);
        return Json.write(envelope);
    }

    long getFailureCount() {
        return failureCount.get();
    }

    // ── Setters consumed by Logback's BeanUtil reflection from logback-spring.xml ─────────────

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /** Setter used only by tests to inject a mock client. */
    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
