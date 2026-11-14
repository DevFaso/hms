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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1910 (round 53): the doctor's UNSIGNED SOAP note reached the patient. {@code GET
 * /me/patient/encounters} (the portal's "Évaluation du médecin", the patient apps) mapped the
 * visit's latest note entry, signed or not, while the after-visit summary for the same visit
 * already drew on the signed note only (#1138). The same class held for the consultant's
 * working text on an open consultation and for treatment plans still in draft or in review.
 *
 * <p>Two lists keep the class closed:
 * <ul>
 *   <li>every patient-facing read method ({@code *ForPortalPatient}, {@code *ForPatientPortal},
 *       {@code toPatientPortal*}) is listed with the token in its body that withholds unsigned or
 *       unfinished content, or why it carries none;</li>
 *   <li>every class that reads a visit's note entries is listed with how it treats an unsigned
 *       one, and a patient-facing one must hold the signed-only accessor.</li>
 * </ul>
 * A new patient read, or a new reader of note entries, fails here until it is looked at.
 */
@DisplayName("D1910: patient-facing reads withhold unsigned or unfinished clinician content")
class PatientFacingUnfinishedContentGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");

    /** A patient-facing read method's declaration. */
    private static final Pattern IDENTIFIER_CALL = Pattern.compile("\\b(\\w++)\\s*+\\(");

    private static boolean isPortalReadName(String name) {
        return name.contains("ForPortalPatient") || name.contains("ForPatientPortal")
            || name.startsWith("toPatientPortal");
    }

    /** A reader of a visit's note entries. */
    private static final Pattern NOTE_READER = Pattern.compile(
        "getEncounterNote\\(\\)|getEncounterNotes\\(\\)|getLatestSignedEncounterNote\\(\\)"
            + "|\\bEncounterNoteRepository\\s++\\w++\\s*+[;=,)]");

    private static final String SIGNED_ONLY = "getLatestSignedEncounterNote()";

    private record Rule(String token, String reason) {
    }

    /** "file#method" to the token its body must hold. */
    private static final Map<String, Rule> PORTAL_READS = Map.ofEntries(
        Map.entry("mapper/EncounterMapper.java#toPatientPortalEncounterResponseDTO", new Rule(SIGNED_ONLY,
            "D1910: the patient reads the latest SIGNED note entry only, never a draft")),
        Map.entry("service/EncounterServiceImpl.java#getEncountersForPortalPatient",
            new Rule("encounterMapper::toPatientPortalEncounterResponseDTO",
                "D1910: the patient's visits go through the patient mapper, not the staff one")),
        Map.entry("service/impl/ConsultationServiceImpl.java#getConsultationsForPortalPatient",
            new Rule("asReadByPatient", "D1910 class: the consultant's text only once the consultation is completed")),
        Map.entry("service/TreatmentPlanServiceImpl.java#listForPortalPatient", new Rule("PATIENT_VISIBLE_STATUSES",
            "D1910 class: approved plans only, never the author's draft or a plan in review")),
        Map.entry("service/DischargeSummaryServiceImpl.java#getDischargeSummariesForPortalPatient",
            new Rule("publishedToPatient", "D1070/#1138: published summaries only; pendingSignature while a note waits")),
        Map.entry("service/PrescriptionServiceImpl.java#getPrescriptionsForPortalPatient",
            new Rule("PrescriptionIssuance.issuedOnly(", "D101/D1537: issued prescriptions only")),
        Map.entry("service/impl/GeneralReferralServiceImpl.java#getReferralsForPortalPatient",
            new Rule("ReferralStatus.DRAFT", "D430: a draft referral is not the patient's")),
        Map.entry("service/impl/PatientLabResultServiceImpl.java#getLabResultsForPatientPortal",
            new Rule("true", "B3: portalView=true redacts an unreleased result to PENDING")),
        Map.entry("service/impl/PatientLabResultServiceImpl.java#countLabResultsForPatientPortal",
            new Rule("true", "counted as the portal list filters")),
        Map.entry("service/impl/PatientMedicationServiceImpl.java#getMedicationsForPatientPortal",
            new Rule("", "delegates to the issued-only reader (NeverSignedOrderReadersGuardTest)")),
        Map.entry("service/impl/PatientMedicationServiceImpl.java#getMedicationsPageForPatientPortal",
            new Rule("PrescriptionIssuance::wasIssued", "issued orders only")),
        Map.entry("service/impl/PatientMedicationServiceImpl.java#countMedicationsForPatientPortal",
            new Rule("PrescriptionIssuance.PROPOSALS", "issued orders only, counted in the database")),
        Map.entry("service/BillingInvoiceServiceImpl.java#getInvoicesForPortalPatient",
            new Rule("InvoiceStatus.DRAFT", "D101: a draft invoice is not issued; no clinical narrative")),
        Map.entry("service/impl/ImmunizationServiceImpl.java#getImmunizationsForPatientPortal",
            new Rule("", "administration records: no author draft state")),
        Map.entry("service/impl/PatientVitalSignServiceImpl.java#getRecentVitalsForPatientPortal",
            new Rule("", "measurements: no author draft state")),
        Map.entry("service/impl/PatientVitalSignServiceImpl.java#getVitalsPageForPatientPortal",
            new Rule("", "measurements: no author draft state")));

    /** Readers of note entries, with the token that shows how an unsigned entry is treated. */
    private static final Map<String, Rule> NOTE_READERS = Map.ofEntries(
        // Patient-facing: the signed entry only.
        Map.entry("mapper/EncounterMapper.java", new Rule(SIGNED_ONLY,
            "the staff DTO carries every entry; the patient DTO the signed one only")),
        Map.entry("mapper/CheckOutMapper.java", new Rule(SIGNED_ONLY, "the after-visit summary: signed note only")),
        Map.entry("service/AfterVisitSummarySections.java", new Rule(SIGNED_ONLY, "the after-visit summary sections")),
        Map.entry("service/DischargeSummaryServiceImpl.java", new Rule(SIGNED_ONLY,
            "the stored summary's follow-up: signed note only; awaitsSignature drives pendingSignature")),
        Map.entry("service/EncounterServiceImpl.java", new Rule(SIGNED_ONLY,
            "the after-visit reconciliation: signed note only; the staff note reads are staff endpoints")),
        // Staff-only readers.
        Map.entry("service/timeline/VisitTimelineRows.java", new Rule("getSignedAt() != null",
            "the staff timeline lists signed notes only")),
        Map.entry("service/impl/ChartReviewServiceImpl.java", new Rule("", "staff chart review")),
        Map.entry("service/ResultReviewServiceImpl.java", new Rule("", "staff result review")),
        Map.entry("service/signature/SignableReportLocator.java", new Rule("", "the signing inbox: staff")),
        Map.entry("service/TreatingClinician.java", new Rule("", "an authorship check; presents nothing")),
        Map.entry("service/MedicalCertificateServiceImpl.java", new Rule("TreatingClinician.isCaller(",
            "an authorship check only; the certificate prints no note text")),
        Map.entry("service/UnsignedNoteFollowUp.java", new Rule("", "reminds the author to sign: staff")));

    @Test
    @DisplayName("every patient-facing read method is listed and still withholds unfinished content")
    void everyPatientFacingReadIsListed() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Path file : sources()) {
            String rel = rel(file);
            if (rel.startsWith("repository/")) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = IDENTIFIER_CALL.matcher(code);
            while (m.find()) {
                if (!isPortalReadName(m.group(1))) {
                    continue;
                }
                String body = SourceScan.methodBody(code, m.group(1));
                if (body == null) {
                    continue;
                }
                String key = rel + "#" + m.group(1);
                found.add(key);
                Rule rule = PORTAL_READS.get(key);
                if (rule == null) {
                    problems.add(key + " is a patient-facing read and is not listed: does it send the patient an"
                        + " unsigned note, a draft or a clinician's unfinished text? Withhold it, then list it.");
                } else if (!body.contains(rule.token())) {
                    problems.add(key + " no longer holds `" + rule.token() + "`: " + rule.reason());
                }
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
        assertThat(found).as("a listed read that no longer exists comes off the list")
            .containsExactlyInAnyOrderElementsOf(PORTAL_READS.keySet());
    }

    @Test
    @DisplayName("every reader of a visit's note entries is listed and says what an unsigned entry does there")
    void everyNoteReaderIsListed() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Path file : sources()) {
            String rel = rel(file);
            if (rel.startsWith("repository/") || rel.startsWith("model/")) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!NOTE_READER.matcher(code).find()) {
                continue;
            }
            found.add(rel);
            Rule rule = NOTE_READERS.get(rel);
            if (rule == null) {
                problems.add(rel + " reads a visit's note entries and is not listed: if the patient reads what it"
                    + " builds, use Encounter.getLatestSignedEncounterNote()");
            } else if (!code.contains(rule.token())) {
                problems.add(rel + " no longer holds `" + rule.token() + "`: " + rule.reason());
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
        assertThat(found).as("a listed reader that no longer reads note entries comes off the list")
            .containsExactlyInAnyOrderElementsOf(NOTE_READERS.keySet());
    }

    @Test
    @DisplayName("D1910: the patient mapper never reuses the staff DTO's note, which is the latest entry signed or not")
    void thePatientMapperDoesNotReuseTheStaffNote() throws IOException {
        String code = SourceScan.stripComments(Files.readString(ROOT.resolve("mapper/EncounterMapper.java"),
            StandardCharsets.UTF_8));
        String body = SourceScan.methodBody(code, "toPatientPortalEncounterResponseDTO");
        assertThat(body).contains("dto.setNote(").contains("setNotePendingSignature(")
            .doesNotContain("dto.getNote()").doesNotContain("getEncounterNote()");
        String consultations = SourceScan.stripComments(Files.readString(
            ROOT.resolve("service/impl/ConsultationServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(consultations, "getConsultationsForPatient"))
            .as("a patient reading /consultations/patient/{own id} gets the same withholding")
            .contains("asReadByPatient");
        String portal = SourceScan.stripComments(Files.readString(
            ROOT.resolve("service/impl/PatientPortalServiceImpl.java"), StandardCharsets.UTF_8));
        for (String staffRead : List.of("toEncounterResponseDTO(", "getEncountersByPatientId(", "listByPatient(",
                "toEncounterNoteResponseDTO(")) {
            assertThat(portal).as("the patient service reads no staff path: " + staffRead).doesNotContain(staffRead);
        }
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String rel(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }
}
