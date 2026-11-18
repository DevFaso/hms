package com.example.hms.config.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Metric;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Micrometer's own meters are pushed over OTLP with the configuration that ships in
 * {@code application.properties}, driven by the same three variables dev and prod set
 * ({@code OTEL_METRICS_ENABLED}, {@code OTEL_METRICS_EXPORT_URL}, {@code GRAFANA_OTLP_AUTH_HEADER}),
 * against a local fake OTLP receiver.
 *
 * <p>Why this exists: on Spring Boot 4 {@link OtlpMetricsExportAutoConfiguration} is
 * {@code @ConditionalOnClass(OpenTelemetryProperties)}, a class from the separate
 * {@code spring-boot-opentelemetry} module. Without that module the registry was never created,
 * so not one Micrometer meter reached Grafana Cloud and nothing was logged. Remove the module
 * from {@code build.gradle} and {@link #pushesMetersWithAuthorizationToTheMetricsPath()} fails.
 *
 * <p>An {@link ApplicationContextRunner} with the two metrics auto-configurations, not a
 * {@code @SpringBootTest}: the classpath condition is what broke, and it is evaluated the same
 * way here, at a fraction of the cost of the whole application context.
 */
class OtlpMetricsExportTest {

    private static final String AUTH = "Basic dGVzdC1pbnN0YW5jZTpnbGNfZmFrZS10b2tlbg==";
    private static final String TOKEN_PART = "dGVzdC1pbnN0YW5jZTpnbGNfZmFrZS10b2tlbg==";

    private record Received(String method, String path, String authorization, String contentType,
                            byte[] body) { }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer receiver;
    private int status = 200;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body));
            byte[] reply = status == 200 ? new byte[0]
                : "authentication error: invalid token".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) {
                exchange.getResponseBody().write(reply);
            }
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    private String metricsUrl() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/otlp/v1/metrics";
    }

    /** The shipped management.* configuration, placeholders and all, resolved per test below. */
    private static List<String> shippedMetricsProperties() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
            new ClassPathResource("application.properties"));
        List<String> pairs = new ArrayList<>();
        for (String name : shipped.stringPropertyNames()) {
            if (name.startsWith("management.") || name.equals("spring.application.name")) {
                pairs.add(name + "=" + shipped.getProperty(name));
            }
        }
        return pairs;
    }

    private ApplicationContextRunner runnerWithShippedConfig() throws IOException {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                MetricsAutoConfiguration.class, OtlpMetricsExportAutoConfiguration.class))
            .withPropertyValues(shippedMetricsProperties().toArray(String[]::new))
            // The Railway variables, exactly as named on dev and prod.
            .withPropertyValues(
                "OTEL_METRICS_ENABLED=true",
                "OTEL_METRICS_EXPORT_URL=" + metricsUrl(),
                "GRAFANA_OTLP_AUTH_HEADER=" + AUTH);
    }

    @Test
    @DisplayName("the shipped config builds an OtlpMeterRegistry and pushes meters with the Authorization header to /otlp/v1/metrics")
    void pushesMetersWithAuthorizationToTheMetricsPath() throws Exception {
        runnerWithShippedConfig().run(context -> {
            assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
            context.getBean(MeterRegistry.class).counter("hms.otlp.probe").increment();
            // Closing a push registry publishes once, synchronously: no sleeping on the step.
        });

        assertThat(received).as("the registry must POST at least once on close").isNotEmpty();
        Received push = received.get(0);
        assertThat(push.method()).isEqualTo("POST");
        assertThat(push.path()).isEqualTo("/otlp/v1/metrics");
        assertThat(push.authorization()).isEqualTo(AUTH);
        assertThat(push.contentType()).isEqualTo("application/x-protobuf");

        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(push.body());
        Map<String, String> resource = new HashMap<>();
        for (KeyValue kv : request.getResourceMetrics(0).getResource().getAttributesList()) {
            resource.put(kv.getKey(), kv.getValue().getStringValue());
        }
        assertThat(resource).containsEntry("service.name", "hms");

        Metric probe = request.getResourceMetricsList().stream()
            .flatMap(rm -> rm.getScopeMetricsList().stream())
            .flatMap(sm -> sm.getMetricsList().stream())
            .filter(m -> m.getName().equals("hms.otlp.probe"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("hms.otlp.probe not in the pushed payload"));
        assertThat(probe.getSum().getAggregationTemporality())
            .as("Grafana Cloud (Mimir) wants cumulative sums")
            .isEqualTo(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE);
        assertThat(probe.getSum().getDataPoints(0).getAttributesList())
            .as("management.metrics.tags.application is applied to every meter")
            .anySatisfy(kv -> {
                assertThat(kv.getKey()).isEqualTo("application");
                assertThat(kv.getValue().getStringValue()).isEqualTo("hms");
            });
    }

    @Test
    @DisplayName("a rejected push is a WARN naming the HTTP status, and never prints the token")
    void rejectedPushIsAWarnWithTheStatusAndNoToken() throws Exception {
        status = 401;
        Logger micrometer = (Logger) LoggerFactory.getLogger(OtlpMeterRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        micrometer.addAppender(appender);
        try {
            runnerWithShippedConfig().run(context ->
                context.getBean(MeterRegistry.class).counter("hms.otlp.probe").increment());
        } finally {
            micrometer.detachAppender(appender);
        }

        assertThat(received).isNotEmpty();
        List<ILoggingEvent> warnings = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .toList();
        assertThat(warnings).as("a 401 from the gateway must be visible in the log").isNotEmpty();
        assertThat(rendered(warnings.get(0))).contains("401");
        assertThat(appender.list).allSatisfy(e ->
            assertThat(rendered(e)).doesNotContain(TOKEN_PART));
    }

    @Test
    @DisplayName("Boot's OTEL_* env mapping is off, so the agent's variables cannot override Micrometer's export")
    void bootDoesNotMapTheAgentsEnvironmentVariables() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
            new ClassPathResource("application.properties"));
        assertThat(shipped.getProperty("management.opentelemetry.map-environment-variables"))
            .isEqualTo("false");
        assertThat(shipped.getProperty("management.opentelemetry.enabled"))
            .as("no in-process OpenTelemetry SDK next to the Java agent")
            .isEqualTo("false");
    }

    private static String rendered(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (var t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append(' ').append(t.getClassName()).append(": ").append(t.getMessage());
        }
        return text.toString();
    }
}
