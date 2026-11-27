package com.example.hms.architecture;

import com.example.hms.service.maternity.ObstetricAlertCurrency;
import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4103 (r81), the class: every source of the urgent-alert ladder has a « no longer urgent » rule. An alert of
 * a source without one is escalated to « non résolue » and an incident after a later normal reading is on the
 * chart, which is what happened to a newborn's vital-set alerts and to an adult's urgent hypertension.
 *
 * <p>A source added to {@link ObstetricAlertEscalationService#SOURCE_TYPES} fails here until it has a case in
 * {@link ObstetricAlertCurrency#noLongerUrgent}, or is listed in
 * {@link ObstetricAlertCurrency#CLOSED_BY_A_PERSON_ONLY} AND below, with the reason no record can close it.
 */
class UrgentAlertClosingRuleGuardTest {

    private static final Path CURRENCY = Path.of(
        "src/main/java/com/example/hms/service/maternity/ObstetricAlertCurrency.java");

    /**
     * The sources only a person closes, each with its reason. Kept here as well as in the class: moving a source
     * out of the rules takes a deliberate change in two places.
     */
    private static final Set<String> PERSON_ONLY = Set.of(
        // The notice of a transfer in labour: an event, with no value behind it to measure again.
        ObstetricAlertEscalationService.SOURCE_TRANSFER,
        // A severe transfusion reaction: an event; the haemovigilance follow-up is a person's.
        ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION);

    @Test
    void everySourceOfTheLadderHasAClosingRuleOrIsDeclaredClosedByAPersonOnly() throws Exception {
        Set<String> withRule = sourcesWithARule();
        assertThat(ObstetricAlertCurrency.CLOSED_BY_A_PERSON_ONLY).isEqualTo(PERSON_ONLY);
        assertThat(withRule).as("a source has a rule or is closed by a person only, never both")
            .doesNotContainAnyElementsOf(PERSON_ONLY);

        Set<String> covered = new TreeSet<>(withRule);
        covered.addAll(PERSON_ONLY);
        assertThat(covered)
            .as("every source of the ladder has a « no longer urgent » rule in ObstetricAlertCurrency.noLongerUrgent")
            .containsExactlyInAnyOrderElementsOf(ObstetricAlertEscalationService.SOURCE_TYPES);
    }

    /** The sources with a case in the switch of {@code noLongerUrgent}, read from the source. */
    private static Set<String> sourcesWithARule() throws IOException, ReflectiveOperationException {
        String source = Files.readString(CURRENCY, StandardCharsets.UTF_8);
        int start = source.indexOf("public boolean noLongerUrgent(");
        int end = source.indexOf("default ->", start);
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        Matcher cases = Pattern.compile("case ObstetricAlertEscalationService\\.(SOURCE_\\w+) ->")
            .matcher(source.substring(start, end));
        Set<String> sources = new TreeSet<>();
        while (cases.find()) {
            sources.add((String) ObstetricAlertEscalationService.class.getField(cases.group(1)).get(null));
        }
        return sources;
    }
}
