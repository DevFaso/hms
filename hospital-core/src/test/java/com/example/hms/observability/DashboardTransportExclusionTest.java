package com.example.hms.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate {@code http_server_requests} panels must exclude the long-lived transport endpoints.
 *
 * <p>{@code /api/ws-chat/**} is SockJS/WebSocket. A healthy connection is held open for its full
 * lifetime, so Micrometer records it as a 25-50 second "request" — against a p95 that should be
 * tens of milliseconds. Traces on dev showed exactly this: the three slowest spans were 50.0s,
 * 25.0s and 25.0s of ws-chat, roughly 800x the next entry. Left in, those samples sit in the top
 * histogram buckets permanently and drag every percentile, Apdex figure, latency SLI and top-N
 * panel with them — the SLO dashboard would report the API as failing while it is perfectly
 * healthy, which is worse than having no dashboard at all.
 *
 * <p>The exclusion is a dashboard variable rather than 30 copies of a literal, because the exact
 * {@code uri} label Micrometer emits for these routes is set by the SockJS handler mapping and is
 * cheaper to correct in one textbox than in every expression.
 *
 * <p>Panels that already pin a specific {@code uri} are left alone: a per-endpoint drill-down is
 * supposed to be able to select ws-chat, and the business KPI panels name their own routes.
 */
class DashboardTransportExclusionTest {

    private static final Path DASHBOARDS = Path.of("..", "grafana", "dashboards");

    private static final String EXCLUSION = "uri!~\"$exclude_uri\"";
    private static final String VARIABLE = "exclude_uri";

    /**
     * Each label selector attached to the metric, checked individually.
     *
     * <p>Per selector rather than per expression, because a ratio has two: an availability panel
     * reading {@code 1 - (sum(…{status=~"5.."}) / sum(…{}))} that excluded the transport from the
     * numerator alone would divide real errors by an inflated request count and report the service
     * as healthier than it is. Both halves have to agree on what they are measuring.
     */
    private static final Pattern METRIC_SELECTOR =
        Pattern.compile("http_server_requests_seconds_\\w+\\{([^}]*)\\}");

    /** A selector on the uri label — {@code by (uri)} is a grouping and deliberately does not count. */
    private static final Pattern URI_SELECTOR = Pattern.compile("uri\\s*[=!]~");

    private record Target(String dashboard, String panel, String expr) { }

    @Test
    @DisplayName("every aggregate http_server_requests panel excludes the WebSocket transport")
    void aggregatePanelsExcludeTransportEndpoints() throws IOException {
        List<Path> files = dashboardFiles();

        assertThat(files)
            .as("expected dashboards under %s — an empty list means this test guards nothing",
                DASHBOARDS.toAbsolutePath().normalize())
            .isNotEmpty();

        var mapper = new ObjectMapper();
        List<Target> unguarded = new ArrayList<>();
        List<String> missingVariable = new ArrayList<>();

        for (Path file : files) {
            JsonNode root = mapper.readTree(Files.readString(file));
            String name = file.getFileName().toString();

            List<Target> needing = new ArrayList<>();
            collectTargets(root, name, null, needing);

            unguarded.addAll(needing);

            boolean usesExclusion = root.toString().contains(VARIABLE);
            if (usesExclusion && !declaresVariable(root)) {
                missingVariable.add(name);
            }
        }

        assertThat(unguarded)
            .as("these panels average over every URI, so a 25-50s SockJS connection lands in the "
                + "same histogram as a 10ms API call and owns the percentile. Add "
                + "`, %s` to the selector.", EXCLUSION)
            .isEmpty();

        assertThat(missingVariable)
            .as("a dashboard whose queries reference $%s must declare it, or every one of those "
                + "panels silently returns no data", VARIABLE)
            .isEmpty();
    }

    /**
     * Targets carrying at least one metric selector that says nothing about {@code uri}, and so
     * sweeps up every endpoint including the transport ones.
     */
    private static void collectTargets(JsonNode node, String dashboard, String title, List<Target> out) {
        if (node.isObject()) {
            String panel = node.hasNonNull("title") ? node.get("title").asText() : title;

            for (JsonNode target : node.path("targets")) {
                String expr = target.path("expr").asText("");
                if (!expr.contains("http_server_requests")) {
                    continue;
                }
                var selectors = METRIC_SELECTOR.matcher(expr);
                while (selectors.find()) {
                    if (!URI_SELECTOR.matcher(selectors.group(1)).find()) {
                        out.add(new Target(dashboard, panel == null ? "(untitled)" : panel, expr));
                        break;
                    }
                }
            }

            node.properties().stream()
                .filter(e -> !"targets".equals(e.getKey()))
                .forEach(e -> collectTargets(e.getValue(), dashboard, panel, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectTargets(child, dashboard, title, out));
        }
    }

    private static boolean declaresVariable(JsonNode root) {
        for (JsonNode v : root.path("templating").path("list")) {
            if (VARIABLE.equals(v.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> dashboardFiles() throws IOException {
        assertThat(DASHBOARDS)
            .as("dashboard directory must be reachable from the module directory")
            .exists();
        try (Stream<Path> paths = Files.list(DASHBOARDS)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();
        }
    }
}
