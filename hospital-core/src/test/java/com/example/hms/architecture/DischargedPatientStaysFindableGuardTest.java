package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1014 (class guard): a patient whose discharge is approved stays
 * findable, so they can be readmitted, booked or seen again.
 *
 * <p>Every patient list, search and picker reads the hospital's ACTIVE
 * registrations ({@code r.active = true}): the registration is the patient's
 * membership of the hospital. The discharge approval cleared that flag, so the
 * patient vanished from every one of them. A discharge ends a stay
 * ({@code stayStatus}), never the membership. Two ways back to the bug, both
 * scanned over the whole of {@code src/main}:
 * <ul>
 *   <li>code that clears a registration's {@code active} flag as a side
 *       effect: an assignment in the entity, a {@code setActive(false)} on a
 *       registration, a JPQL or SQL update writing it false. Only the
 *       administrator's explicit edit ({@code setActive(dto.isActive())})
 *       changes it;</li>
 *   <li>a query on registrations that leaves out a stay status
 *       ({@code stayStatus <> …}, {@code NOT IN (…DISCHARGED…)}): a patient
 *       lookup that drops discharged patients is the same disappearance.</li>
 * </ul>
 */
class DischargedPatientStaysFindableGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern ENTITY_CLEARS = Pattern.compile("\\bthis\\.active\\s*=\\s*false");
    private static final Pattern SETTER_CLEARS = Pattern.compile(
        "\\b((?:[a-qs-zA-QS-Z0-9_]|[Rr](?!eg))*+[Rr]eg\\w*+)\\s*+\\.\\s*+setActive\\(\\s*+(false|Boolean\\.FALSE)\\s*+\\)");
    private static final Pattern QUERY_CLEARS = Pattern.compile(
        "(?is)update\\s+(PatientHospitalRegistration|clinical\\.patient_hospital_registrations)\\b[^\"]*?"
            + "\\b(active|is_active)\\s*=\\s*(false|0)");
    private static final Pattern QUERY_EXCLUDES_STAY = Pattern.compile(
        // A query's comparison: against a literal, a parameter, a list or the
        // enum constant (a Java `stayStatus != this.stayStatus` is not a query).
        "(?is)(stayStatus|stay_status)\\s*(?:(?:<>|!=)\\s*(?:['(:]|[\\w.]*DISCHARGED)|not\\s+in\\b)");

    @Test
    void nothingButTheAdministratorsEditEndsARegistration() throws IOException {
        List<String> hits = new ArrayList<>();
        int registrationFiles = 0;
        for (Path f : javaFiles()) {
            String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
            String rel = MAIN.relativize(f).toString().replace('\\', '/');
            boolean registrationFile = src.contains("PatientHospitalRegistration")
                || src.contains("patient_hospital_registrations");
            if (!registrationFile) {
                continue;
            }
            registrationFiles++;
            if (rel.equals("model/PatientHospitalRegistration.java")) {
                collect(ENTITY_CLEARS, src, rel, hits);
            }
            collect(SETTER_CLEARS, src, rel, hits);
            collect(QUERY_CLEARS, src, rel, hits);
            collect(QUERY_EXCLUDES_STAY, src, rel, hits);
        }
        assertThat(registrationFiles).as("files that touch registrations").isGreaterThan(20);
        assertThat(hits)
            .as("a registration ended, or a discharged patient left out of a lookup, as a side effect (QA D1014): "
                + "a discharge ends the stay (stayStatus), never the registration; " + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void anApprovedDischargeKeepsThePatientRegistered() {
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setActive(true);
        registration.markDischarged();
        assertThat(registration.getStayStatus()).isEqualTo(PatientStayStatus.DISCHARGED);
        assertThat(registration.isActive()).as("QA D1014: the discharged patient stays registered").isTrue();
    }

    @Test
    void theScanSeesTheOldShapes() {
        assertThat(ENTITY_CLEARS.matcher("this.active = false;").find()).isTrue();
        assertThat(SETTER_CLEARS.matcher("registration.setActive(false);").find()).isTrue();
        assertThat(SETTER_CLEARS.matcher("registration.setActive(dto.isActive());").find()).isFalse();
        assertThat(QUERY_CLEARS.matcher(
            "UPDATE PatientHospitalRegistration r SET r.active = false WHERE r.id = :id").find()).isTrue();
        assertThat(QUERY_EXCLUDES_STAY.matcher("AND r.stayStatus <> 'DISCHARGED'").find()).isTrue();
        assertThat(QUERY_EXCLUDES_STAY.matcher("AND r.stayStatus NOT IN (:closed)").find()).isTrue();
    }

    private static void collect(Pattern pattern, String src, String rel, List<String> hits) {
        Matcher m = pattern.matcher(src);
        while (m.find()) {
            hits.add(rel + ":" + SourceScan.lineOf(src, m.start()) + " " + m.group().replaceAll("\\s+", " "));
        }
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }
}
