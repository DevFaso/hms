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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The long-lived transport endpoint must be out of the aggregate SLIs, and visible everywhere else.
 *
 * <p>{@code /ws-chat} is SockJS. A healthy connection is held open for its full lifetime, so
 * Micrometer times it as a 25-50 second "request". Dev traces showed exactly that: the three
 * slowest spans were 50.0s, 25.0s and 25.0s, roughly 800x the next entry. Those samples sit in the
 * top histogram buckets permanently and drag every percentile, Apdex figure, latency SLI and top-N
 * panel with them, so the SLO dashboard reports the API as failing while it is perfectly healthy.
 *
 * <p>"Exclude it everywhere" is the wrong fix, and the first version of this change made exactly
 * that mistake. The rule the dashboards and rules actually follow, and that this test pins:
 *
 * <ul>
 *   <li><b>Duration</b> ({@code _bucket}) selectors always exclude it — the 50s samples are the
 *       defect, and they dominate a per-URI top-N just as badly as an application-wide p99.</li>
 *   <li><b>Count</b> selectors exclude it, with one exception: a breakdown <em>by</em> URI that
 *       filters on 5xx must keep it. That table is the first thing an engineer opens during an
 *       incident, and hiding a failing endpoint from it is a worse failure than the latency
 *       distortion this change set out to fix. A by-URI breakdown that only counts or times is a
 *       ranking, not a fault detector, and the transport wins any ranking trivially.</li>
 * </ul>
 *
 * <p>Every property below has already been got wrong once in this change's own history:
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
 *   <li><b>An expression with no selector at all.</b> {@code rate(http_server_requests_seconds_count[5m])}
 *       has no label braces, so a check that iterates over matched selectors sees none and passes
 *       in silence — the one shape that needs the exclusion most.</li>
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

    /**
     * The metric, plus its label set.
     *
     * <p>The label set is matched as "anything but braces, or a whole {@code ${...}} variable".
     * A plain {@code [^}]*} truncates at the first closing brace, which in Grafana is routinely
     * part of a variable reference — {@code le="${apdex_tolerable}"} in the endpoint deep-dive is
     * one. Truncating there drops the rest of the label set, so a correctly guarded selector fails
     * whenever the variable happens to sit before the exclusion, and a wrong one passes whenever a
     * {@code uri=~} sits before it. The deep-dive only passed by that ordering luck.
     */
    private static final Pattern METRIC_SELECTOR = Pattern.compile(
        "http_server_requests_seconds_(\\w+)\\{((?:[^{}]|\\$\\{[^{}]*\\})*)\\}");

    /** The same metric with no label set at all — guarded by nothing, so worth naming. */
    private static final Pattern BARE_METRIC =
        Pattern.compile("http_server_requests_seconds_(\\w+)\\s*[\\[)]");

    /** A positive pin on uri — the panel or rule has chosen its endpoints deliberately. */
    private static final Pattern URI_PIN = Pattern.compile("uri\\s*=~");

    /** Any negative uri matcher, so a near-miss such as $exclude_url cannot pass as "guarded". */
    private static final Pattern URI_EXCLUDE = Pattern.compile("uri\\s*!~");

    /**
     * {@code by (...)} groupings only. {@code without (uri)} is the opposite operation — it strips
     * the label — so a substring search for "uri)" reads {@code sum without (uri, method)} as a
     * per-URI breakdown and quietly exempts an aggregate from the whole guard.
     */
    private static final Pattern BY_GROUPING = Pattern.compile("\\bby\\s*\\(([^)]*)\\)");

    /**
     * A filter on 5xx, which makes a panel or rule a fault detector rather than a ranking.
     *
     * <p>This is the line between the two halves of the rule. A per-URI <em>error</em> breakdown
     * must show the transport: it is the first table an engineer opens during an incident, and
     * hiding a failing endpoint from it is worse than the latency distortion this change fixes. A
     * per-URI breakdown that is only counting or timing is a ranking, and the transport wins any
     * such ranking trivially — several polls a second per open client — while telling the reader
     * nothing and pushing a real endpoint out of the top 10.
     */
    private static final Pattern ERROR_STATUS = Pattern.compile("status\\s*=~\\s*\"5");

    /** Suffixes that carry a duration, and so are poisoned by a held-open connection. */
    private static final Set<String> DURATION_SUFFIXES = Set.of("bucket", "sum", "max");

    /** The uri labels Micrometer actually emits for the SockJS endpoint. */
    private static final List<String> TRANSPORT_LABELS = List.of("/ws-chat/**", "/ws-chat");

    /** Ordinary endpoints, which must survive the filter. */
    private static final List<String> REAL_ENDPOINTS =
        List.of("/me/patient/medications", "/auth/login", "/patients/{id}", "/chat/unread-count");

    /**
     * Recorded series that are unfiltered on purpose, each with the reason.
     *
     * <p>Named one by one rather than pattern-matched: an exemption is a decision, and adding one
     * should mean editing this test and justifying it here.
     */
    private static final Set<String> UNFILTERED_BY_DESIGN = Set.of(
        // HmsBackendNoTraffic's heartbeat. Filtering it makes the alert fire on a healthy but
        // idle backend; a transport poll is not user traffic, but it does prove the process is up.
        "app:http_requests_all:rate5m");

    private record Finding(String where, String detail) { }

    @Test
    @DisplayName("aggregate dashboard selectors exclude the transport; per-URI breakdowns keep it")
    void dashboardSelectorsFollowTheRule() throws IOException {
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
                problems.add(new Finding(name + " (dashboard)",
                    "references $" + VARIABLE + " but never declares it, so every query using it "
                        + "interpolates to nothing and those panels go blank"));
            }
        }

        assertThat(problems)
            .as("a duration selector, and any count selector aggregated across URIs, must carry "
                + "exactly `%s`; a count selector grouped BY uri must not", EXCLUSION_VAR)
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
    void recordingRulesFollowTheRule() throws IOException {
        assertThat(RECORDING_RULES).as("recording rules must be reachable").exists();
        String body = Files.readString(RECORDING_RULES);

        List<Finding> problems = new ArrayList<>();
        String[] blocks = body.split("- record: ");

        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            if (!block.contains("http_server_requests_seconds_")) {
                continue;
            }
            String rule = block.split("\n", 2)[0].trim();
            if (UNFILTERED_BY_DESIGN.contains(rule)) {
                continue;
            }
            String expr = stripComments(block);

            check(expr, rule, EXCLUSION_LITERAL, problems);
        }

        assertThat(problems)
            .as("these recorded series feed HmsP99LatencyHigh and the multi-window burn-rate "
                + "alerts; without `%s` a held-open SockJS connection pages the on-call engineer, "
                + "and transport polls dilute the 5xx ratio so a real outage is downgraded. A rule "
                + "that must stay unfiltered belongs in UNFILTERED_BY_DESIGN, with its reason",
                EXCLUSION_LITERAL)
            .isEmpty();
    }

    @Test
    @DisplayName("every recorded series an alert reads actually exists")
    void alertsReferenceRecordedSeries() throws IOException {
        Path alerts = RECORDING_RULES.resolveSibling("alerts.yml");
        assertThat(alerts).as("alert rules must be reachable").exists();

        // Both files are flat enough to read with a line scanner, which keeps a YAML parser off
        // the test classpath. `- record: x` declares a series; `app:...`/`app_uri:...` anywhere in
        // alerts.yml reads one.
        Set<String> recorded = Pattern.compile("- record:\\s*(\\S+)")
            .matcher(Files.readString(RECORDING_RULES))
            .results()
            .map(r -> r.group(1))
            .collect(java.util.stream.Collectors.toSet());

        assertThat(recorded)
            .as("no recorded series parsed out of %s — this guard would be vacuous", RECORDING_RULES)
            .isNotEmpty();

        Matcher references = Pattern.compile("\\b(app(?:_uri)?:[a-z0-9_:]+)")
            .matcher(stripComments(Files.readString(alerts)));

        List<String> dangling = new ArrayList<>();
        while (references.find()) {
            String series = references.group(1);
            if (!recorded.contains(series)) {
                dangling.add(series);
            }
        }

        // This change renamed the series behind HmsBackendNoTraffic (to app:http_requests_all:rate5m,
        // so liveness reads an unfiltered total) and added two for the transport alert. A rename
        // that misses one side is invisible: Prometheus evaluates the alert against a series that
        // does not exist, which is never > a threshold, so the alert simply never fires again.
        assertThat(dangling)
            .as("these series are read by an alert in alerts.yml but recorded nowhere in "
                + "recording.yml, so the alert can never fire")
            .isEmpty();
    }

    /**
     * Applies the duration/count rule to one expression.
     *
     * @param required the exact exclusion this file should carry — a variable on a dashboard, a
     *                 literal in a recording rule
     */
    private static void check(String expr, String where, String required, List<Finding> out) {
        boolean byUri = groupsByUri(expr);
        boolean sawSelector = false;

        Matcher selectors = METRIC_SELECTOR.matcher(expr);
        while (selectors.find()) {
            sawSelector = true;
            String suffix = selectors.group(1);
            String labels = selectors.group(2);
            boolean duration = DURATION_SUFFIXES.contains(suffix);

            if (URI_PIN.matcher(labels).find()) {
                continue;
            }
            if (byUri && !duration && ERROR_STATUS.matcher(labels).find()) {
                if (labels.contains(required)) {
                    out.add(new Finding(where,
                        "is a per-URI error breakdown but hides the transport -> {" + labels + "}. "
                            + "A 5xx storm on /ws-chat has to stay visible as its own row."));
                }
                continue;
            }
            if (!labels.contains(required)) {
                String why = URI_EXCLUDE.matcher(labels).find()
                    ? "excludes uri with something other than " + required
                    : (duration ? "times every uri, transport included"
                                : "counts across every uri, transport included");
                out.add(new Finding(where, why + " -> {" + labels + "}"));
            }
        }

        // An expression can reference the metric with no label set at all, in which case the loop
        // above never ran. That shape is unguarded by construction, so it cannot be a pass.
        if (!sawSelector && !byUri && BARE_METRIC.matcher(expr).find()) {
            out.add(new Finding(where,
                "uses http_server_requests_seconds_* with no label set, so it has no exclusion "
                    + "and no uri pin — add `{" + required + "}`"));
        }
    }

    /** True when the expression breaks results down by uri, via {@code by (...)} and not {@code without}. */
    private static boolean groupsByUri(String expr) {
        Matcher groupings = BY_GROUPING.matcher(expr);
        while (groupings.find()) {
            for (String label : groupings.group(1).split(",")) {
                if ("uri".equals(label.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** YAML comments explain the rules; they must not be read as part of them. */
    private static String stripComments(String block) {
        return block.lines()
            .map(line -> {
                int hash = line.indexOf('#');
                return hash < 0 ? line : line.substring(0, hash);
            })
            .reduce("", (a, b) -> a + "\n" + b);
    }

    private static void collect(JsonNode node, String file, String title, List<Finding> out) {
        if (node.isObject()) {
            String panel = node.hasNonNull("title") ? node.get("title").asText() : title;

            for (JsonNode target : node.path("targets")) {
                String expr = target.path("expr").asText("");
                if (!expr.contains("http_server_requests")) {
                    continue;
                }
                check(expr, file + " / " + (panel == null ? "(untitled)" : panel),
                    EXCLUSION_VAR, out);
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
