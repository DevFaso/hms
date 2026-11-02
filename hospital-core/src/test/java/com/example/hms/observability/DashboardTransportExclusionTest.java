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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate {@code http_server_requests} queries must exclude the long-lived transport endpoint.
 *
 * <p>{@code /ws-chat} is SockJS. A healthy connection is held open for its full lifetime, so
 * Micrometer times it as a 25-50 second "request" — against a p95 that should be tens of
 * milliseconds. Dev traces showed exactly that: the three slowest spans were 50.0s, 25.0s and
 * 25.0s, roughly 800x the next entry. Those samples sit in the top histogram buckets permanently
 * and drag every percentile, Apdex figure, latency SLI and top-N panel with them, so the SLO
 * dashboard reports the API as failing while it is perfectly healthy.
 *
 * <p>Three separate things have to hold, and each has already been got wrong once:
 *
 * <ol>
 *   <li><b>Every selector, not every expression.</b> A ratio has two. An availability panel that
 *       excluded the transport from the 5xx numerator but not from the denominator would divide
 *       real errors by an inflated request count and under-report them.</li>
 *   <li><b>The pattern has to match a label that exists.</b> {@code
 *       server.servlet.context-path=/api} is stripped from Micrometer's {@code uri} tag, so the
 *       label is {@code /ws-chat/**}, not {@code /api/ws-chat/**}. A pattern anchored on the
 *       context path matches nothing — and because {@code !~} removes only what it matches, every
 *       query still runs, still looks guarded, and filters nothing at all.</li>
 *   <li><b>Alert rules too.</b> {@code grafana/rules/recording.yml} feeds the pager. Fixing only
 *       the dashboards would leave {@code HmsP99LatencyHigh} firing on idle browser tabs, and let
 *       the dashboard and the alert disagree about the same SLO.</li>
 * </ol>
 */
class DashboardTransportExclusionTest {

    private static final Path DASHBOARDS = Path.of("..", "grafana", "dashboards");
    private static final Path RECORDING_RULES = Path.of("..", "grafana", "rules", "recording.yml");

    /** Dashboards indirect through a variable, so the pattern is correctable without an edit. */
    private static final String VARIABLE = "exclude_uri";
    private static final String EXCLUSION_VAR = "uri!~\"$" + VARIABLE + "\"";

    /** Recording rules are evaluated server-side, where no dashboard variable exists. */
    private static final String TRANSPORT_PATTERN = "(/api)?/ws-chat.*";
    private static final String EXCLUSION_LITERAL = "uri!~\"" + TRANSPORT_PATTERN + "\"";

    /** Every label selector attached to the metric, checked on its own. */
    private static final Pattern METRIC_SELECTOR =
        Pattern.compile("http_server_requests_seconds_\\w+\\{([^}]*)\\}");

    /** A positive pin on uri — the panel has chosen its endpoints deliberately. */
    private static final Pattern URI_PIN = Pattern.compile("uri\\s*=~");

    /** Any negative uri matcher, so a near-miss such as $exclude_url cannot pass as "guarded". */
    private static final Pattern URI_EXCLUDE = Pattern.compile("uri\\s*!~");

    /** The uri labels Micrometer actually emits for the SockJS endpoint. */
    private static final List<String> TRANSPORT_LABELS = List.of("/ws-chat/**", "/ws-chat");

    /** Ordinary endpoints, which must survive the filter. */
    private static final List<String> REAL_ENDPOINTS =
        List.of("/me/patient/medications", "/auth/login", "/patients/{id}", "/chat/unread-count");

    private record Finding(String file, String panel, String detail) { }

    @Test
    @DisplayName("every aggregate dashboard selector excludes the transport, and only via $exclude_uri")
    void dashboardSelectorsAreGuarded() throws IOException {
        List<Path> files = dashboardFiles();
        assertThat(files)
            .as("expected dashboards under %s — an empty list means this test guards nothing",
                DASHBOARDS.toAbsolutePath().normalize())
            .isNotEmpty();

        var mapper = new ObjectMapper();
        List<Finding> problems = new ArrayList<>();

        for (Path file : files) {
            String body = Files.readString(file);
            JsonNode root = mapper.readTree(body);
            String name = file.getFileName().toString();

            collect(root, name, null, problems);

            if (body.contains("$" + VARIABLE) && !declaresVariable(root)) {
                problems.add(new Finding(name, "(dashboard)",
                    "references $" + VARIABLE + " but never declares it, so every query using it "
                        + "interpolates to nothing and those panels go blank"));
            }
        }

        assertThat(problems)
            .as("each selector must either pin its own uri (uri=~...) or carry exactly `%s`",
                EXCLUSION_VAR)
            .isEmpty();
    }

    @Test
    @DisplayName("the exclusion pattern matches the uri label Micrometer really emits")
    void exclusionPatternMatchesRealLabels() throws IOException {
        List<String> patterns = new ArrayList<>();
        patterns.add(TRANSPORT_PATTERN);

        for (Path file : dashboardFiles()) {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file));
            for (JsonNode v : root.path("templating").path("list")) {
                if (VARIABLE.equals(v.path("name").asText())) {
                    patterns.add(v.path("query").asText());
                    patterns.add(v.path("current").path("value").asText());
                }
            }
        }

        assertThat(patterns)
            .as("no exclusion pattern found to check — the guard would be vacuous")
            .hasSizeGreaterThan(1);

        for (String pattern : patterns) {
            // PromQL anchors label matchers fully, so matches() is the right comparison.
            Pattern compiled = Pattern.compile(pattern);

            for (String label : TRANSPORT_LABELS) {
                assertThat(compiled.matcher(label).matches())
                    .as("pattern [%s] does not match the transport label [%s]. The uri tag omits "
                        + "server.servlet.context-path=/api, so a pattern anchored on /api matches "
                        + "nothing and the exclusion silently filters zero series.", pattern, label)
                    .isTrue();
            }
            for (String label : REAL_ENDPOINTS) {
                assertThat(compiled.matcher(label).matches())
                    .as("pattern [%s] also matches the ordinary endpoint [%s], which would drop "
                        + "real traffic out of the SLIs", pattern, label)
                    .isFalse();
            }
        }
    }

    @Test
    @DisplayName("aggregate recording rules exclude the transport, so alerts and dashboards agree")
    void recordingRulesAreGuarded() throws IOException {
        assertThat(RECORDING_RULES).as("recording rules must be reachable").exists();
        String body = Files.readString(RECORDING_RULES);

        List<String> unguarded = new ArrayList<>();
        for (String block : body.split("- record: ")) {
            if (!block.contains("http_server_requests_seconds_")) {
                continue;
            }
            String rule = block.split("\n", 2)[0].trim();

            // Rules that break down BY uri keep the transport as its own visible series; it is the
            // ones summing across every endpoint that have to drop it.
            if (block.contains("uri,") || block.contains("uri)")) {
                continue;
            }

            Matcher selectors = METRIC_SELECTOR.matcher(block);
            boolean guarded = selectors.find();
            selectors.reset();
            while (selectors.find()) {
                if (!selectors.group(1).contains(EXCLUSION_LITERAL)) {
                    guarded = false;
                }
            }
            if (!guarded) {
                unguarded.add(rule);
            }
        }

        assertThat(unguarded)
            .as("these recorded series feed HmsP99LatencyHigh and the multi-window burn-rate "
                + "alerts; without `%s` a held-open SockJS connection pages the on-call engineer, "
                + "and transport polls dilute the 5xx ratio so a real outage is downgraded",
                EXCLUSION_LITERAL)
            .isEmpty();
    }

    /** Selectors that say nothing useful about {@code uri}, so they sweep up the transport. */
    private static void collect(JsonNode node, String file, String title, List<Finding> out) {
        if (node.isObject()) {
            String panel = node.hasNonNull("title") ? node.get("title").asText() : title;

            for (JsonNode target : node.path("targets")) {
                String expr = target.path("expr").asText("");
                if (!expr.contains("http_server_requests")) {
                    continue;
                }
                Matcher selectors = METRIC_SELECTOR.matcher(expr);
                while (selectors.find()) {
                    String labels = selectors.group(1);
                    if (labels.contains(EXCLUSION_VAR) || URI_PIN.matcher(labels).find()) {
                        continue;
                    }
                    String why = URI_EXCLUDE.matcher(labels).find()
                        ? "excludes uri with something other than " + EXCLUSION_VAR
                        : "averages over every uri, transport included";
                    out.add(new Finding(file, panel == null ? "(untitled)" : panel,
                        why + " -> {" + labels + "}"));
                }
            }

            node.properties().stream()
                .filter(e -> !"targets".equals(e.getKey()))
                .forEach(e -> collect(e.getValue(), file, panel, out));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, file, title, out));
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
