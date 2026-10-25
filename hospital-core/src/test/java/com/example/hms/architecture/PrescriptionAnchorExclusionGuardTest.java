package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D759 → D791: a prescription written outside any visit hangs on an
 * encounter of its own ({@code Encounter.PRESCRIPTION_ANCHOR_NOTE}). D759 left
 * it out of the staff and portal visit lists; the chart review's "Revue de
 * dossier" kept counting it ("4 CONSULTATIONS" next to "Aucune consultation"),
 * and so did the tracker board, the day's walk-ins, the encounter report, the
 * dashboards and the patient timeline.
 *
 * <p>Every production call of an {@code EncounterRepository} read that can
 * return an anchor must leave it out in the same statement (a
 * {@code isPrescriptionAnchor()} / {@code notPrescriptionAnchor()} filter, or
 * a visit query built on {@code EncounterRepository.NOT_PRESCRIPTION_ANCHOR}),
 * or be listed below with the reason it may keep the anchor. The list only
 * shrinks: an entry that no longer matches an unfiltered call fails too.
 */
class PrescriptionAnchorExclusionGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** The reads that can return an anchor: by patient, by staff, by hospital, or everything. */
    private static final List<String> ANCHOR_RETURNING_READS = List.of(
        "findByPatient_Id",
        "findByStaff_Id",
        "findByPatient_IdOrderByEncounterDateDesc",
        "findByPatient_IdAndHospital_IdOrderByEncounterDateDesc",
        "findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc",
        "findByPatient_IdAndHospital_Id",
        "findAllByPatient_IdAndHospital_Id",
        "findByHospital_IdAndEncounterDateBetween",
        "findByHospital_IdAndStatus",
        "findByStaff_IdAndStatus",
        "findByStaff_IdAndHospital_Id",
        "countByHospital_Id",
        "count",
        "findAll");

    private static final Pattern CALL = Pattern.compile(
        "\\b(?:encounterRepository|encounterRepo)\\s*\\.\\s*(" + String.join("|", ANCHOR_RETURNING_READS) + ")\\s*\\(");

    private static final List<String> MARKERS =
        List.of("isPrescriptionAnchor", "notPrescriptionAnchor", "PRESCRIPTION_ANCHOR_NOTE");

    /** File#read → why the anchor may stay. Shrink only. */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
        Map.entry("EncounterFhirResourceProvider.java#findByPatient_Id",
            "FHIR interchange: the anchor is the MedicationRequest.encounter its prescription references"),
        Map.entry("FhirBulkExportRunner.java#findByPatient_IdAndHospital_Id",
            "FHIR bulk export: same, the exported MedicationRequest references resolve"),
        Map.entry("PatientEverythingService.java#findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc",
            "FHIR $everything: same, a complete record keeps the anchor its prescriptions reference"),
        Map.entry("TreatmentRelationshipResolverImpl.java#findByPatient_IdAndHospital_Id",
            "a prescription written for the patient is a treatment relationship, visit or not"),
        Map.entry("EncounterServiceImpl.java#findAll",
            "getAllEncounters: the Specification starts from notPrescriptionAnchor(), built statements above"),
        Map.entry("ClinicalDashboardServiceImpl.java#findByStaff_IdAndStatus",
            "IN_PROGRESS only; an anchor is always COMPLETED"),
        Map.entry("DoctorWorklistServiceImpl.java#findByStaff_IdAndStatus",
            "open statuses only; an anchor is always COMPLETED"),
        Map.entry("ResultReviewServiceImpl.java#findByStaff_IdAndStatus",
            "IN_PROGRESS only; an anchor is always COMPLETED"),
        Map.entry("MortalityServiceImpl.java#findByPatient_Id",
            "open encounters only (OPEN_ENCOUNTERS); an anchor is always COMPLETED"),
        Map.entry("PatientStoryboardServiceImpl.java#findByPatient_Id",
            "loadActiveEncounter: non-terminal statuses only; the last-visit read filters the anchor"));

    @Test
    @DisplayName("every read that can return a prescription anchor leaves it out, or says why it may keep it")
    void anchorsAreLeftOutOfVisitCountsAndLists() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> allowedSeen = new HashSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getParent().getFileName().toString().equals("repository")) {
                    continue;
                }
                String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = CALL.matcher(source);
                while (m.find()) {
                    int end = source.indexOf(';', m.end());
                    String statement = source.substring(m.start(), end < 0 ? source.length() : end);
                    if (MARKERS.stream().anyMatch(statement::contains)) {
                        continue;
                    }
                    String key = file.getFileName() + "#" + m.group(1);
                    if (ALLOWED.containsKey(key)) {
                        allowedSeen.add(key);
                    } else {
                        offenders.add(key + " (line " + (source.substring(0, m.start()).split("\n", -1).length) + ")");
                    }
                }
            }
        }
        assertThat(offenders)
            .as("filter the prescription anchor (Encounter#isPrescriptionAnchor) in the same statement, "
                + "use a NOT_PRESCRIPTION_ANCHOR visit query, or add an ALLOWED entry with the reason")
            .isEmpty();
        assertThat(allowedSeen)
            .as("stale ALLOWED entries: the call is gone or now filters the anchor, so remove the entry")
            .containsExactlyInAnyOrderElementsOf(ALLOWED.keySet());
    }

    @Test
    @DisplayName("the chart review reads visits, never the raw patient encounter page (QA D791)")
    void chartReviewReadsVisits() throws IOException {
        String source = Files.readString(MAIN.resolve("service/impl/ChartReviewServiceImpl.java"), StandardCharsets.UTF_8);
        assertThat(source)
            .contains("findVisitsByPatientIdAndHospitalIdIn(")
            .contains("findVisitsByPatientId(")
            .doesNotContain("findByPatient_IdOrderByEncounterDateDesc(")
            .doesNotContain("findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc(");
    }

    /** Line comments that start a line and block comments: a ';' in a comment is not the end of a statement. */
    private static String stripComments(String source) {
        String noBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source)
            .replaceAll(mr -> mr.group().replaceAll("[^\\n]", " "));
        return noBlocks.replaceAll("(?m)^(\\s*)//.*$", "$1");
    }
}
