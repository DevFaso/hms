package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA CX-002 (round 33): the platform analytics told a super-admin that
 * hospitals with 14 and 8 staff had none, and that departments with 95
 * encounters had none: the builder said {@code .staffCount(0)} and
 * {@code .encounterCount(0)}. A dashboard number is read as authoritative, so
 * no platform dashboard service sets a metric of its DTO to a literal: it
 * counts it, or leaves it null for the screen to say "unavailable".
 *
 * <p>Also QA CX-005: an entity of the platform schema with a unique constraint
 * is reached by a form. Its duplicate must be refused on the field that
 * conflicts, before the constraint answers "this record already exists",
 * naming nothing. A plan's tier code had no such check.</p>
 */
@DisplayName("Platform: no made-up dashboard number, no fieldless duplicate")
class PlatformAnalyticsHonestNumbersGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** The services behind /super-admin/analytics and the super-admin dashboard. */
    private static final List<String> DASHBOARD_SERVICES = List.of(
        "service/impl/PlatformAnalyticsServiceImpl.java",
        "service/SuperAdminDashboardServiceImpl.java",
        "service/impl/SuperAdminAuditAggregationServiceImpl.java",
        "service/impl/SuperAdminPlatformRegistryServiceImpl.java",
        "service/impl/SuperAdminIntegrationHealthServiceImpl.java",
        "service/impl/KpiDashboardServiceImpl.java");

    /** A builder or setter handing a metric a literal: {@code .staffCount(0)}, {@code .encounterTrend(List.of())}. */
    private static final Pattern MADE_UP = Pattern.compile(
        "\\.(?:set)?(\\w*+(?:[cC]ount|[tT]otal|[tT]rend|[rR]ate|[pP]ercent|[aA]verage|[sS]um)\\w*+)\\(\\s*+"
            + "(-?\\d++L?+|-?\\d++\\.\\d++|BigDecimal\\.ZERO|Collections\\.emptyList\\(\\)|List\\.of\\(\\)|Map\\.of\\(\\))"
            + "\\s*+\\)");

    @Test
    @DisplayName("a dashboard service never sets a metric to a literal")
    void noDashboardMetricIsALiteral() throws IOException {
        List<String> madeUp = new ArrayList<>();
        for (String service : DASHBOARD_SERVICES) {
            madeUp.addAll(madeUpMetrics(service, SourceScan.stripComments(read(service))));
        }
        assertThat(madeUp).as("count it, or leave it null so the screen says unavailable (QA CX-002)"
            + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("the rule sees the two literals of QA CX-002")
    void theRuleSeesTheDefect() {
        assertThat(madeUpMetrics("x", ".staffCount(0)\n.encounterCount(0L)\n.encounterTrend(Collections.emptyList())"))
            .hasSize(3);
        assertThat(madeUpMetrics("x", ".staffCount(staff.getOrDefault(id, 0L))\n.setCount(total)")).isEmpty();
    }

    @Test
    @DisplayName("a platform service never cuts a dashboard list with a literal limit")
    void noDashboardListIsCut() throws IOException {
        assertThat(SourceScan.stripComments(read("service/impl/PlatformAnalyticsServiceImpl.java")))
            .as("QA CX-003: the department table stopped at ten and said nothing")
            .doesNotContain(".limit(");
    }

    private static List<String> madeUpMetrics(String file, String source) {
        List<String> found = new ArrayList<>();
        Matcher m = MADE_UP.matcher(source);
        while (m.find()) {
            found.add(file + ":" + SourceScan.lineOf(source, m.start()) + " " + m.group());
        }
        return found;
    }

    /**
     * Every entity of model/platform with a unique constraint, and how its
     * duplicate is refused: the service and the field it names, or why a form
     * cannot reach the constraint. A new unique entity fails until it is declared.
     */
    private static final Map<String, String[]> UNIQUE_ENTITIES = Map.ofEntries(
        Map.entry("SubscriptionPlan", new String[] {
            "service/impl/SubscriptionServiceImpl.java", "\"tierCode\", \"subscription.plan.tierCode.duplicate\""}),
        Map.entry("PlatformReleaseWindow", new String[] {
            "service/impl/SuperAdminPlatformRegistryServiceImpl.java", "\"name:\" + MessageUtil.resolve(\"platform.releaseWindow.duplicate\""}),
        Map.entry("OrganizationPlatformService", new String[] {
            "service/platform/impl/PlatformRegistryServiceImpl.java", "\"serviceType:\" + MessageUtil.resolve(locale"}),
        Map.entry("MllpAllowedSender", new String[] {
            "service/platform/impl/MllpAllowedSenderServiceImpl.java", "\"sendingApplication\", \"mllpAllowedSender.duplicate\""}),
        Map.entry("AuditSavedSearch", new String[] {
            "service/impl/AuditSavedSearchServiceImpl.java", "new FieldBusinessException(\"name\", DUPLICATE_NAME_KEY"}));

    /** Unique on the platform schema, and why no form's typed value reaches the constraint. */
    private static final Map<String, String> NOT_A_TYPED_VALUE = Map.of(
        "ApiKey", "key_hash is generated by the server",
        "HospitalPlatformServiceLink", "both ids come from the URL: the 409 platform.hospitalLink.exists has no request field to name",
        "DepartmentPlatformServiceLink", "both ids come from the URL: the 409 platform.departmentLink.exists has no request field to name",
        "AdtIntakeProviderConfig", "an upsert: a second save of the pair updates its row",
        "FeatureFlagOverride", "an upsert: a second save of the key updates its row",
        "OrganizationSubscription", "assigning a plan cancels the running subscription first, in the same transaction");

    @Test
    @DisplayName("QA CX-005: a platform entity's duplicate is refused on its field, before the constraint")
    void everyUniquePlatformEntityRefusesOnItsField() throws IOException {
        List<String> undeclared = new ArrayList<>();
        try (Stream<Path> entities = Files.list(MAIN.resolve("model/platform"))) {
            for (Path entity : entities.toList()) {
                String source = Files.readString(entity, StandardCharsets.UTF_8);
                String name = entity.getFileName().toString().replace(".java", "");
                boolean unique = source.contains("@UniqueConstraint(") || source.contains("unique = true")
                    // Unique by a table constraint or a partial index the entity does not declare.
                    || "AuditSavedSearch".equals(name) || "OrganizationSubscription".equals(name);
                if (unique && !UNIQUE_ENTITIES.containsKey(name) && !NOT_A_TYPED_VALUE.containsKey(name)) {
                    undeclared.add(name);
                }
            }
        }
        assertThat(undeclared).as("declare how a duplicate is refused on its field (QA CX-005)"
            + SourceScan.MANDATE).isEmpty();
        for (Map.Entry<String, String[]> entity : UNIQUE_ENTITIES.entrySet()) {
            assertThat(read(entity.getValue()[0])).as(entity.getKey() + " refuses its duplicate on its field")
                .contains(entity.getValue()[1]);
        }
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8);
    }
}
