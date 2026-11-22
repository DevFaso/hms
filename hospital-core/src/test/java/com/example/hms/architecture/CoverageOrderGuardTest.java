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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3250 (r70): the desk read only the coverage flagged primary while the invoice took any coverage in force, so a
 * patient's only policy, linked without « principale », was billed to the payer while the desk said « Problème
 * d'assurance » and the check-in tick recorded nothing. Every money path now picks a patient's coverage through ONE
 * rule, {@code CoverageOrder}. This guard keeps it that way:
 * <ul>
 *   <li>no main code outside {@code CoverageOrder} selects or orders coverages by the primary flag itself
 *       ({@code PatientInsurance::isPrimary}, or a comparator on {@code isPrimary()});</li>
 *   <li>the invoice's coverage ({@code InvoiceCoverageService.coverageOn}) and the desk's attestation
 *       ({@code ReceptionServiceImpl.stampInsuranceVerified}) both go through it.</li>
 * </ul>
 */
@DisplayName("Every money path picks a patient's coverage by one rule (CoverageOrder)")
class CoverageOrderGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path RULE = MAIN.resolve("service/billing/CoverageOrder.java");
    private static final Pattern SELECTS_BY_PRIMARY = Pattern.compile(
        "PatientInsurance\\s*+::\\s*+isPrimary"
            + "|Comparator\\s*+\\.\\s*+comparing\\(\\s*+\\(\\s*+PatientInsurance\\b[^)]*\\)\\s*+->\\s*+!?\\s*+\\w++"
            + "\\.isPrimary\\(\\)");

    @Test
    @DisplayName("no main code outside CoverageOrder selects a coverage by its primary flag")
    void noSelectionByThePrimaryFlagOutsideTheRule() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                if (file.normalize().equals(RULE.normalize())) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = SELECTS_BY_PRIMARY.matcher(code);
                while (m.find()) {
                    offenders.add(file.getFileName() + ":" + SourceScan.lineOf(code, m.start()));
                }
            }
        }
        assertThat(offenders)
            .as("a coverage picked by its primary flag outside CoverageOrder: the desk and the invoice disagree on"
                + " which policy applies (QA D3250)")
            .isEmpty();
    }

    @Test
    @DisplayName("the invoice and the desk's attestation both pick the coverage through CoverageOrder")
    void theInvoiceAndTheDeskShareTheRule() throws IOException {
        String coverage = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/billing/InvoiceCoverageService.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(coverage, "coverageOn")).contains("CoverageOrder.ordered(");

        String reception = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/ReceptionServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(reception, "stampInsuranceVerified")).contains("deskCoverage(");
        String desk = SourceScan.methodBody(reception, "deskCoverage");
        assertThat(desk).contains("billedCoverage(").contains("CoverageOrder.");
    }

    @Test
    @DisplayName("the scan itself: a stream filtered on the primary flag, or sorted on it, is a finding")
    void theScanFindsTheClass() {
        assertThat(SELECTS_BY_PRIMARY.matcher("rows.stream().filter(PatientInsurance::isPrimary).findFirst()").find())
            .isTrue();
        assertThat(SELECTS_BY_PRIMARY.matcher(
            ".sorted(Comparator.comparing((PatientInsurance insurance) -> !insurance.isPrimary())").find()).isTrue();
        assertThat(SELECTS_BY_PRIMARY.matcher(".primary(insurance.isPrimary())").find()).isFalse();
    }
}
