package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2924 (r66; the class of r63 D2322): every deferral, override, dispense and claim audit row was written with no
 * hospital, so the audit service could not anchor it on the actor's assignment and the hospital admin's audit log
 * (which reads rows by hospital) never listed one. "Check the audit log" could not be done by the hospital.
 *
 * <p>The rule: an audit row written by the pharmacy or by billing names its hospital. Every
 * {@code AuditEventRequestDTO.builder()} chain in those services carries {@code .hospitalId(...)} (the audit service
 * then anchors the row on the actor's own assignment there, or stamps a system row with it) or an explicit
 * {@code .assignmentId(...)}. A new emitter that forgets it fails here, not in a QA round.
 */
@DisplayName("Pharmacy and billing audit rows name their hospital")
class MoneyAuditHospitalGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String BUILDER = "AuditEventRequestDTO.builder()";

    /** The pharmacy and billing services: the two packages, and the services of the same domains outside them. */
    private static boolean inScope(String relativePath) {
        return relativePath.startsWith("service/pharmacy/")
            || relativePath.startsWith("service/billing/")
            || relativePath.equals("service/BillingInvoiceServiceImpl.java")
            || relativePath.equals("service/InvoiceItemServiceImpl.java")
            || relativePath.equals("service/impl/PharmacyServiceImpl.java")
            || relativePath.equals("service/impl/MedicationCatalogItemServiceImpl.java");
    }

    @Test
    void everyPharmacyAndBillingAuditRowNamesItsHospital() throws IOException {
        List<String> unanchored = new ArrayList<>();
        int chains = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (!inScope(relative)) {
                    continue;
                }
                String code = SourceScan.stripComments(read(file));
                int at = code.indexOf(BUILDER);
                while (at >= 0) {
                    chains++;
                    String chain = chainFrom(code, at);
                    if (!chain.contains(".hospitalId(") && !chain.contains(".assignmentId(")) {
                        unanchored.add(relative + ":" + SourceScan.lineOf(code, at));
                    }
                    at = code.indexOf(BUILDER, at + BUILDER.length());
                }
            }
        }
        assertThat(chains).as("audit builder chains found in the pharmacy and billing services").isGreaterThan(15);
        assertThat(unanchored)
            .as("audit rows written with no hospital: the hospital admin's audit log cannot list them (D2924)")
            .isEmpty();
    }

    /** The builder chain from {@code builder()} to its {@code .build()}. */
    private static String chainFrom(String code, int builderAt) {
        int end = code.indexOf(".build()", builderAt);
        return end < 0 ? code.substring(builderAt) : code.substring(builderAt, end);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
