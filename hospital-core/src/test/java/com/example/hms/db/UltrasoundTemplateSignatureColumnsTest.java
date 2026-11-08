package com.example.hms.db;

import com.example.hms.model.UltrasoundReport;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1525 review: V282 clears a legacy report's gestational age only when the
 * row still bears the template's signature, which means EVERY column a
 * clinician could have typed is at its template or empty state. This test
 * reads the entity's columns and fails when one is neither listed below as
 * metadata (who, when, workflow, the age itself) nor tested in the V282
 * signature, in the backup insert and in the gestational-age update alike. A new
 * column on the entity therefore has to be placed here on purpose.
 */
class UltrasoundTemplateSignatureColumnsTest {

    /** Not something a clinician states about the scan: who, when, workflow, the age being judged (the order and hospital are @JoinColumns, not read here). */
    private static final Set<String> METADATA = Set.of(
        "scan_date", "scan_performed_by", "scan_performed_by_credentials",
        "gestational_age_at_scan", "observations_stated", "report_finalized_at", "report_finalized_by",
        "report_reviewed_by_provider", "patient_notified", "patient_notified_at");

    @Test
    void everyClinicianEnterableColumnIsInTheTemplateSignature() throws Exception {
        String sql = v282().lines().filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
        List<String> statements = Arrays.asList(sql.split(";"));
        String gaUpdate = statements.stream()
            .filter(s -> s.contains("SET gestational_age_at_scan = NULL")).findFirst().orElseThrow();
        String backup = statements.stream()
            .filter(s -> s.contains("INSERT INTO clinical.ultrasound_report_legacy_backup")).findFirst().orElseThrow();

        Set<String> enterable = new TreeSet<>();
        for (Field field : UltrasoundReport.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && !column.name().isEmpty() && !METADATA.contains(column.name())) {
                enterable.add(column.name());
            }
        }
        assertThat(enterable).as("the entity's enterable columns").hasSizeGreaterThan(30);
        for (String column : enterable) {
            Pattern word = Pattern.compile("\\b" + column + "\\b");
            assertThat(word.matcher(gaUpdate).find())
                .as(column + " is enterable but the V282 gestational-age signature does not test it")
                .isTrue();
            assertThat(word.matcher(backup).find())
                .as(column + " is enterable but the V282 backup's signature does not test it")
                .isTrue();
        }
        assertThat(gaUpdate).contains("report_reviewed_by_provider IS NOT TRUE");
        assertThat(backup).contains("report_reviewed_by_provider IS NOT TRUE");
    }

    @Test
    void theMetadataListNamesRealColumns() {
        Set<String> columns = Arrays.stream(UltrasoundReport.class.getDeclaredFields())
            .map(f -> f.getAnnotation(Column.class)).filter(c -> c != null).map(Column::name)
            .collect(Collectors.toSet());
        assertThat(columns).as("a stale metadata entry").containsAll(METADATA);
    }

    private static String v282() throws Exception {
        try (InputStream in = UltrasoundTemplateSignatureColumnsTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V282__ultrasound_observations_stated.sql")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
