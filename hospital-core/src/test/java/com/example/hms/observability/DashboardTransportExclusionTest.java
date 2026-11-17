package com.example.hms.observability;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 *   <li><b>Mixing or competing.</b> The distortion needs the transport either to mix with other
 *       endpoints, as in an application-wide percentile, or to compete with them, as in a
 *       {@code topk}. Those must exclude it. A plain per-URI breakdown does neither — the
 *       transport is one row, 50s is honestly what it takes, nothing else is affected — so the
 *       guard deliberately declines to decide those, which is why the unfiltered per-URI
 *       {@code app_uri:http_request_duration_seconds:p99_15m} rule is left alone.</li>
 *   <li><b>Unless it would hide a failure.</b> A per-URI view filtered on 5xx must keep the
 *       transport, top-10 of failing endpoints included. That table is the first thing an engineer
 *       opens during an incident, and hiding a failing endpoint from it is a worse bug than the
 *       latency distortion this change set out to fix.</li>
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
     * <p>The label set is matched as "anything but braces, a whole quoted value, or a whole
     * {@code ${...}} variable". The quoted-value alternative is what lets a Spring path pattern
     * through: {@code uri!="/patients/{id}"} contains literal braces, and without it the selector
     * matched nothing at all and was silently left unjudged — the precise silent-pass class this
     * class exists to prevent.
     * A plain {@code [^}]*} truncates at the first closing brace, which in Grafana is routinely
     * part of a variable reference — {@code le="${apdex_tolerable}"} in the endpoint deep-dive is
     * one. Truncating there drops the rest of the label set, so a correctly guarded selector fails
     * whenever the variable happens to sit before the exclusion, and a wrong one passes whenever a
     * {@code uri=~} sits before it. The deep-dive only passed by that ordering luck.
     */
    private static final Pattern METRIC_SELECTOR = Pattern.compile(
        "http_server_requests_seconds_(\\w+)\\{((?:[^{}\"]|\"[^\"]*\"|\\$\\{[^{}]*\\})*)\\}");

    /** The same metric with no label set at all — guarded by nothing, so worth naming. */
    private static final Pattern BARE_METRIC =
        Pattern.compile("http_server_requests_seconds_(\\w+)\\s*[\\[)]");

    /**
     * A positive pin on uri — the panel or rule has chosen its endpoints deliberately.
     *
     * <p>Captures the pinned pattern, because the pin has to be inspected rather than merely
     * noticed. {@code uri=~"$uri"} against a variable whose All option interpolates to
     * {@code .*} is not a choice of endpoints at all: it is an application-wide aggregate wearing
     * a pin, with the 50s samples back in — the exact shape this change fixed in the deep-dive.
     * A bare {@code uri=~} exemption would let it return with every test green.
     *
     * <p>Both operators are matched. The deep-dive uses the exact form on purpose: its values come
     * from {@code label_values} and are Spring patterns, so the regex form would hand PromQL
     * {@code /ws-chat/**} or {@code /**} verbatim, and {@code **} is a bad repetition operator in
     * RE2 — a parse error on every panel.
     */
    private static final Pattern URI_PIN = Pattern.compile("uri\\s*=(~?)\\s*\"([^\"]*)\"");

    /**
     * A pattern that pins nothing: it admits every endpoint, transport included.
     *
     * <p>Note what is NOT here: an empty alternative. An earlier version read
     * {@code "|\\.\\*|..."}, whose leading empty branch matches the empty string — so every
     * variable without a default, the deep-dive's own {@code uri} among them, was classed as a
     * wildcard, and a correctly pinned {@code uri=~"$textbox"} panel would have been reported.
     * An unset default is not a match-all; it is Grafana resolving the first option on load.
     */
    private static final Pattern MATCH_ALL =
        Pattern.compile("\\.\\*|\\.\\+|\\(\\?s\\)\\.\\*");

    /**
     * Regex constructs RE2 does not implement, and {@code java.util.regex} does.
     *
     * <p>Lookahead, lookbehind and backreferences are absent from RE2 by design (it guarantees
     * linear time), and nested repetition such as {@code **} is rejected as a bad operator. A
     * pattern using any of them compiles in this test and then fails in Prometheus.
     *
     * <p>Only a quantifier followed by {@code *} or {@code +} counts as nested. The lazy
     * forms {@code .*?}, {@code .+?} and {@code ??} are perfectly valid RE2 — an earlier
     * {@code [*+?]{2,}} rejected those too, failing a correct pattern with an explanation
     * that did not apply to it.
     */
    private static final Pattern RE2_UNSUPPORTED =
        Pattern.compile("\\(\\?[=!<]|\\\\[1-9]|[*+?][*+]");

    /** Any negative uri matcher, so a near-miss such as $exclude_url cannot pass as "guarded". */
    private static final Pattern URI_EXCLUDE = Pattern.compile("uri\\s*!~");

    /**
     * {@code by (...)} groupings only. {@code without (uri)} is the opposite operation — it strips
     * the label — so a substring search for "uri)" reads {@code sum without (uri, method)} as a
     * per-URI breakdown and quietly exempts an aggregate from the whole guard.
     */
    private static final Pattern BY_GROUPING = Pattern.compile("\\bby\\s*\\(([^)]*)\\)");

    /** A {@code by (...)} immediately after an aggregation's arguments — PromQL's trailing form. */
    private static final Pattern TRAILING_GROUPING =
        Pattern.compile("\\s*by\\s*\\(([^)]*)\\)");

    /** An aggregation operator with its optional {@code by (...)} clause, for the mixed-level check. */
    private static final Pattern AGGREGATION = Pattern.compile(
        "\\b(sum|avg|count|min|max|stddev|stdvar|quantile)\\s*(?:by\\s*\\(([^)]*)\\)\\s*)?\\(");

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

    /**
     * A top-N, where the transport does not merely appear but outranks everything.
     *
     * <p>Second half of the "mix or compete" test. A per-URI table shows the transport as one
     * honest row among many; a {@code topk(10, ...)} pushes a real endpoint out of the list,
     * because a connection held open for 50 seconds wins a latency ranking outright and several
     * polls a second per client wins a volume one.
     */
    private static final Pattern TOPK = Pattern.compile("\\btopk\\s*\\(");

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
            JsonNode root = mapper.readTree(Files.readString(file));
            String name = file.getFileName().toString();

            collect(root, name, null, wildcardVariables(root), problems);

            // Checked against the QUERIES, not the raw file text. A dashboard is entitled to
            // mention $exclude_uri in a panel or variable description — the endpoint deep-dive
            // explains there why it is exempt from it — and reading prose as a query reference
            // made the guard demand a variable the dashboard deliberately does not have.
            if (referencesVariableInAQuery(root) && !declaresVariable(root)) {
                problems.add(new Finding(name + " (dashboard)",
                    "a query references $" + VARIABLE + " but the dashboard never declares it, so "
                        + "it interpolates to nothing and those panels go blank"));
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

        // Every declared default must be the SAME pattern the recording rules hard-code. Checking
        // only that each matches the transport would let the dashboards and the rules drift apart
        // — a dashboard that also excluded, say, /actuator would disagree with the alert about the
        // very SLO it displays, which is the third property this class exists to pin.
        assertThat(patterns)
            .as("every dashboard's $%s default must equal the literal the recording rules use "
                + "(%s), or a dashboard and the alert it mirrors compute different SLIs",
                VARIABLE, TRANSPORT_PATTERN)
            .containsOnly(TRANSPORT_PATTERN);

        for (String pattern : patterns) {
            // Prometheus matches labels with RE2, not java.util.regex, and the two disagree in
            // both directions. Java accepts lookahead and backreferences that RE2 rejects
            // outright, so a pattern that compiles here can still 400 every query that uses it —
            // all 40 selectors and 14 recorded series at once. Checked before compiling, because
            // Pattern.compile is exactly the step that would wave it through.
            assertThat(RE2_UNSUPPORTED.matcher(pattern).find())
                .as("pattern [%s] uses a construct RE2 does not support (lookahead, lookbehind, "
                    + "backreference or nested repetition). Prometheus would reject the whole "
                    + "label matcher, so every panel and rule carrying it returns a parse error — "
                    + "and java.util.regex compiles it happily, which is why this is checked "
                    + "separately.", pattern)
                .isFalse();

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
        // BOTH alert files. grafana/rules/alerts.yml is the Prometheus-native one, whose
        // Alertmanager is commented out in prometheus.yml — so it notifies nobody, and checking
        // only it would have left the path that DOES page unguarded. The provisioned file is the
        // sole consumer of the two new transport series, which is exactly the kind of reference a
        // rename breaks silently.
        List<Path> alertFiles = List.of(
            RECORDING_RULES.resolveSibling("alerts.yml"),
            RECORDING_RULES.getParent().resolveSibling("provisioning").resolve("alerting")
                .resolve("alert-rules.yml"));
        for (Path alerts : alertFiles) {
            assertThat(alerts).as("alert rules must be reachable: %s", alerts).exists();
        }

        // The files are flat enough to read with a line scanner, which keeps a YAML parser off the
        // test classpath. `- record: x` declares a series; `app:...`/`app_uri:...` in an alert
        // file reads one.
        // stripComments on this side too. The alert side was already stripped, but reading the
        // recording rules raw meant a commented-out `# - record: x` still counted as declared —
        // so deleting a rule by commenting it out would leave every alert that reads it passing
        // this check while never being able to fire again.
        Set<String> recorded = Pattern.compile("- record:\\s*(\\S+)")
            .matcher(stripComments(Files.readString(RECORDING_RULES)))
            .results()
            .map(r -> r.group(1))
            .collect(java.util.stream.Collectors.toSet());

        assertThat(recorded)
            .as("no recorded series parsed out of %s — this guard would be vacuous", RECORDING_RULES)
            .isNotEmpty();

        List<String> dangling = new ArrayList<>();
        for (Path alerts : alertFiles) {
            Matcher references = Pattern.compile("\\b(app(?:_uri)?:[a-z0-9_:]+)")
                .matcher(stripComments(Files.readString(alerts)));
            while (references.find()) {
                String series = references.group(1);
                if (!recorded.contains(series)) {
                    dangling.add(alerts.getFileName() + " -> " + series);
                }
            }
        }

        // This change renamed the series behind HmsBackendNoTraffic (to app:http_requests_all:rate5m,
        // so liveness reads an unfiltered total) and added two for the transport alert. A rename
        // that misses one side is invisible: Prometheus evaluates the alert against a series that
        // does not exist, which is never > a threshold, so the alert simply never fires again.
        assertThat(dangling)
            .as("these series are read by an alert file but recorded nowhere in recording.yml, so "
                + "that alert can never fire")
            .isEmpty();
    }

    /**
     * Applies the duration/count rule to one expression.
     *
     * @param required the exact exclusion this file should carry — a variable on a dashboard, a
     *                 literal in a recording rule
     */
    private static void check(String expr, String where, String required, List<Finding> out) {
        // Recording rules carry no template variables, so nothing there can interpolate to a
        // match-all and every uri=~ in them is a literal pin. Dashboards pass their own set.
        check(expr, where, required, Set.of(), out);
    }

    private static void check(String expr, String where, String required,
                              Set<String> wildcardVariables, List<Finding> out) {
        // Decided for the whole expression, never per selector. A per-URI 5xx RATIO has two
        // selectors — a numerator carrying status=~"5.." and a denominator carrying no status at
        // all. Judging each on its own would forbid the exclusion on the numerator and demand it
        // on the denominator, so the panel could not be written correctly in either direction:
        // exactly the numerator/denominator mismatch the first property exists to prevent.
        boolean byUri = groupsByUri(expr);
        boolean errorFocused = ERROR_STATUS.matcher(expr).find();
        boolean ranking = TOPK.matcher(expr).find();

        // An expression that aggregates BOTH ways gets no verdict, because neither verdict is
        // right for it. `sum by (uri)(5xx) / scalar(sum(total))` has a per-URI numerator, which
        // must keep the transport, and an application-wide denominator, which must drop it — so
        // one per-expression classification exempts a half, and the numerator/denominator
        // mismatch the first property exists to prevent walks back in. Rather than guess, the
        // guard says it cannot judge this shape and asks for it to be split.
        if (byUri && mixesAggregationLevels(expr)) {
            out.add(new Finding(where,
                "aggregates both by uri and across all uris in one expression, so no single "
                    + "transport rule is correct for it: the per-URI half must keep the transport "
                    + "and the aggregate half must drop it. Split it into two targets, or pin the "
                    + "aggregate half's uri explicitly."));
            return;
        }

        // The distortion needs the transport to either MIX with other endpoints (an aggregate
        // percentile) or COMPETE with them (a top-N). A plain per-URI breakdown does neither: the
        // transport is one row, 50s is what it honestly takes, and nothing else is affected. So
        // only aggregates and rankings are required to exclude it.
        boolean mustExclude = !byUri || ranking;

        // Except when it would hide a failure. A per-URI view filtered on 5xx is a fault detector,
        // and the transport belongs in it — including in a top-10 of failing endpoints, which is
        // precisely where you want to see it.
        boolean mustShowTransport = byUri && errorFocused;

        Matcher selectors = METRIC_SELECTOR.matcher(expr);
        while (selectors.find()) {
            String labels = selectors.group(2);
            if (isRealPin(labels, wildcardVariables)) {
                continue;
            }
            judge(labels.contains(required), DURATION_SUFFIXES.contains(selectors.group(1)),
                mustExclude, mustShowTransport, URI_EXCLUDE.matcher(labels).find(), required,
                where, "{" + labels + "}", out);
        }

        // The metric can also appear with no label set at all. That shape is unguarded by
        // construction, and it must be judged on its own terms rather than only when nothing else
        // matched: a ratio with one braced and one brace-less selector, or a brace-less duration
        // grouped by uri, would otherwise slip through on the back of its better-written sibling.
        Matcher bare = BARE_METRIC.matcher(expr);
        while (bare.find()) {
            judge(false, DURATION_SUFFIXES.contains(bare.group(1)), mustExclude,
                mustShowTransport, false, required, where, "no label set at all", out);
        }
    }

    /** One selector, already classified. Kept separate so braced and bare shapes share a verdict. */
    private static void judge(boolean excludes, boolean duration, boolean mustExclude,
                              boolean mustShowTransport, boolean wrongExclusion, String required,
                              String where, String shape, List<Finding> out) {
        // `duration` is used only to word the finding: it no longer changes any verdict.
        // Not gated on !duration. The rule is about hiding a failure, and a per-URI top-10 of
        // p99 among 5xx responses is as much a fault detector as a count is — more so, since a
        // failing transport request returns fast and will not dominate a latency ranking the way
        // a healthy held-open connection does. Gating this on !duration forced that panel to
        // exclude the transport, contradicting the rule this class documents.
        if (mustShowTransport) {
            if (excludes) {
                out.add(new Finding(where,
                    "is a per-URI error breakdown but hides the transport -> " + shape
                        + ". A 5xx storm on /ws-chat has to stay visible as its own row."));
            }
            return;
        }
        if (!mustExclude) {
            // A per-URI breakdown that neither mixes populations nor ranks them. Either way is
            // defensible, so this is the one case the guard deliberately declines to decide.
            return;
        }
        if (!excludes) {
            String why = wrongExclusion
                ? "excludes uri with something other than " + required
                : (duration ? "times every uri, transport included"
                            : "counts across every uri, transport included");
            out.add(new Finding(where, why + " -> " + shape));
        }
    }

    /**
     * True when a {@code uri} matcher really narrows the series, so the exclusion is unnecessary.
     *
     * <p>An exact match always does. A regex match does only if its pattern is not a match-all and
     * does not interpolate a variable that can become one.
     */
    private static boolean isRealPin(String labels, Set<String> wildcardVariables) {
        Matcher pin = URI_PIN.matcher(labels);
        while (pin.find()) {
            boolean regex = !pin.group(1).isEmpty();
            String pattern = pin.group(2);
            if (!regex) {
                return true;
            }
            if (MATCH_ALL.matcher(pattern).matches()) {
                continue;
            }
            String variable = pattern.startsWith("$")
                ? pattern.substring(1).replaceAll("[{}]", "")
                : null;
            if (variable != null && wildcardVariables.contains(variable)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * True when one expression contains both a by-uri aggregation and one that is not by uri.
     *
     * <p>Counts aggregation operators rather than {@code by} clauses, so a bare {@code sum(...)}
     * or a {@code scalar(sum(...))} — which has no {@code by} at all and is therefore
     * application-wide — is detected alongside a {@code sum by (uri)}.
     */
    private static boolean mixesAggregationLevels(String expr) {
        Matcher aggregations = AGGREGATION.matcher(expr);
        while (aggregations.find()) {
            String grouping = aggregations.group(2) != null
                ? aggregations.group(2)
                : trailingGrouping(expr, expr.indexOf('(', aggregations.end() - 1));
            boolean thisOneByUri = grouping != null && Stream.of(grouping.split(","))
                .anyMatch(label -> "uri".equals(label.trim()));
            if (!thisOneByUri) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code by (...)} clause that FOLLOWS an aggregation's arguments, if there is one.
     *
     * <p>PromQL accepts the modifier on either side — {@code sum by (uri) (rate(x))} and
     * {@code sum(rate(x)) by (uri)} mean the same thing — and the dashboards here use the
     * trailing form throughout, inside {@code topk} and {@code histogram_quantile}. Reading only
     * the leading form made every one of those look like an un-grouped, application-wide
     * aggregation, so the mixed-level check reported five panels that were perfectly fine. The
     * scan is balanced rather than a regex because the argument list contains its own parentheses.
     *
     * @param open index of the aggregation's opening parenthesis
     * @return the label list, or {@code null} if this aggregation has no trailing modifier
     */
    private static String trailingGrouping(String expr, int open) {
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    Matcher trailing = TRAILING_GROUPING.matcher(expr.substring(i + 1));
                    return trailing.lookingAt() ? trailing.group(1) : null;
                }
            }
        }
        return null;
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

    /**
     * Strips YAML comments, which explain the rules and must not be read as part of them.
     *
     * <p>Quote-aware, because a {@code #} inside a quoted scalar is data, not a comment. The
     * runbook_url values in both alert files are exactly that — every one ends in
     * {@code .md#anchor} — so a naive cut at the first {@code #} truncates the line there.
     * Harmless for a URL, but {@link #alertsReferenceRecordedSeries} scans this output, so a
     * one-line alert whose expression followed a quoted {@code #} would lose its series
     * reference and the dangling-series check would pass on nothing at all.
     */
    private static String stripComments(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            char quote = 0;
            int cut = line.length();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '#') {
                    cut = i;
                    break;
                }
            }
            out.append(line, 0, cut).append('\n');
        }
        return out.toString();
    }

    /**
     * Walks a dashboard's panels.
     *
     * <p>There is deliberately no convenience overload defaulting {@code wildcardVariables} to
     * empty. An empty set makes every {@code uri=~"$var"} read as a real pin, which is exactly
     * the "aggregate wearing a pin" hole {@link #URI_PIN} exists to close — so the set is a
     * required argument, and a caller has to think about where it comes from.
     */
    private static void collect(JsonNode node, String file, String title,
                                Set<String> wildcardVariables, List<Finding> out) {
        if (node.isObject()) {
            String panel = node.hasNonNull("title") ? node.get("title").asText() : title;

            for (JsonNode target : node.path("targets")) {
                String expr = target.path("expr").asText("");
                if (!expr.contains("http_server_requests")) {
                    continue;
                }
                check(expr, file + " / " + (panel == null ? "(untitled)" : panel),
                    EXCLUSION_VAR, wildcardVariables, out);
            }

            node.properties().stream()
                .filter(e -> !"targets".equals(e.getKey()))
                .forEach(e -> collect(e.getValue(), file, panel, wildcardVariables, out));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, file, title, wildcardVariables, out));
        }
    }

    /**
     * Template variables that can interpolate to a match-all, so a {@code uri=~} on one is not a
     * pin. An {@code includeAll} variable gets {@code allValue} — or {@code .*} when none is set.
     */
    private static Set<String> wildcardVariables(JsonNode root) {
        Set<String> wildcards = new java.util.HashSet<>();
        for (JsonNode v : root.path("templating").path("list")) {
            String allValue = v.path("allValue").asText("");
            boolean includeAll = v.path("includeAll").asBoolean(false);
            boolean currentIsAll = MATCH_ALL.matcher(
                v.path("current").path("value").asText("")).matches();
            if ((includeAll && (allValue.isEmpty() || MATCH_ALL.matcher(allValue).matches()))
                    || currentIsAll) {
                wildcards.add(v.path("name").asText());
            }
        }
        return wildcards;
    }

    /** True when some {@code targets[].expr} interpolates the variable. */
    private static boolean referencesVariableInAQuery(JsonNode node) {
        if (node.isObject()) {
            for (JsonNode target : node.path("targets")) {
                if (target.path("expr").asText("").contains("$" + VARIABLE)) {
                    return true;
                }
            }
            return node.properties().stream()
                .filter(e -> !"targets".equals(e.getKey()))
                .anyMatch(e -> referencesVariableInAQuery(e.getValue()));
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (referencesVariableInAQuery(child)) {
                    return true;
                }
            }
        }
        return false;
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
