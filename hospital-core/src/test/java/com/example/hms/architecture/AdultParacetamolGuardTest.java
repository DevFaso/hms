package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 34 (adult paracetamol, class: a dose limit one path does not reach). Every prescribing
 * path, the CDS hooks and the signature run the one engine, and the engine is the one place a rule
 * context is built, so no path can reach the rules without the paracetamol facts. (A CDS hook sends
 * no frequency, so there the daily total counts one dose of its draft: tasklist.md.) The rule stays
 * what the guidance calls it, an alert: the source says "alert", no hard stop or review was asked,
 * and raising it is a clinician's decision, not a side effect.
 */
@DisplayName("Adult paracetamol: one engine reaches every path, and its cards stay alerts")
class AdultParacetamolGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path RULE = MAIN.resolve("cdshooks/rules/AdultParacetamolRule.java");
    private static final Path ENGINE = MAIN.resolve("cdshooks/rules/CdsRuleEngine.java");

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String code(Path file) throws IOException {
        return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void onlyTheEngineBuildsARuleContext() throws IOException {
        Set<String> builders = new TreeSet<>();
        for (Path source : javaSources()) {
            if (code(source).contains("new CdsRuleContext(")) {
                builders.add(MAIN.relativize(source).toString().replace('\\', '/'));
            }
        }
        assertThat(builders).containsExactly("cdshooks/rules/CdsRuleEngine.java");
    }

    @Test
    void theEngineReadsTheFactsForEveryContext() throws IOException {
        assertThat(code(ENGINE)).contains("readParacetamolFacts(patient, hospitalId, proposedMedicationName, catalogItem, excludedPrescriptionId)")
            .contains("return paracetamolFacts.read(patient, hospitalId, name, item, editedOrderId);");
    }

    @Test
    void theRuleOnlyEverAlerts() throws IOException {
        assertThat(code(RULE))
            .contains("ALERT = CdsCard.Indicator.WARNING")
            .doesNotContain("Indicator.CRITICAL")
            .doesNotContain("Boolean.TRUE")
            // The eight-argument card: no hardStop, no reviewRequired.
            .contains("null, null, null, UUID.randomUUID().toString());");
    }

    @Test
    void childrenStayWithThePaediatricRule() throws IOException {
        assertThat(code(RULE)).contains("PediatricDoseRule.isAdult(context.patient())")
            .contains("PediatricDoseRule.dosesOnADosingDay(")
            .doesNotContain("getDateOfBirth").doesNotContain("Period.between");
    }
}
