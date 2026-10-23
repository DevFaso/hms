package com.example.hms.service.recordaccess;

import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D623 / D652 architecture guard: a patient list hospital B can read never
 * shows a row its by-id read answers 404 for.
 *
 * <p>Two halves:
 * <ol>
 *   <li>{@link #PATIENT_LISTS}: every patient list endpoint the re-test replayed
 *       ({@code d652.js}), with its by-id read and the service both go through.
 *       Each pair must still be mapped, and when the record has a never-sent
 *       state the service must apply {@link NeverSentRule} to both.</li>
 *   <li>A source scan: every class that reads a record with a never-sent state
 *       (prescriptions, imaging orders, referrals) across
 *       {@code readableHospitalIds} must pass its rows through {@link NeverSentRule}.
 *       A new chart section, timeline or list that forgets it fails here.</li>
 * </ol>
 */
class CrossHospitalListByIdAgreementTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** One patient list B can read, its by-id read, and how the two agree. */
    private record ListPair(String listMapping, String byIdMapping, String controller, String service,
                            boolean neverSentState) {
    }

    /** The five lists of the re-test (scripts/r9-retest/tenancy-admissions/d652.js). */
    private static final List<ListPair> PATIENT_LISTS = List.of(
        new ListPair("/patient/{patientId}", "/{referralId}", "GeneralReferralController",
            "service/impl/GeneralReferralServiceImpl.java", true),
        new ListPair("/orders/patient/{patientId}", "/orders/{orderId}", "ImagingOrderController",
            "service/impl/ImagingOrderServiceImpl.java", true),
        // GET /prescriptions?patientId= and GET /prescriptions/{id}
        new ListPair("@GetMapping", "/{id}", "PrescriptionController",
            "service/PrescriptionServiceImpl.java", true),
        // GET /lab-orders?patientId= (search, handled-by scope) and /lab-orders/{id} (isHandledBy):
        // the same hospital set on both sides, and no never-sent state.
        new ListPair("@GetMapping", "/{id}", "LabOrderController",
            "service/LabOrderServiceImpl.java", false),
        // GET /patients/{p}/documents and /{documentId}: the same requireRegisteredAt gate on both,
        // and a patient upload has no never-sent state.
        new ListPair("@GetMapping", "/{documentId}", "PatientDocumentStaffController",
            "service/impl/PatientDocumentServiceImpl.java", false));

    /** Reads of a never-sent-capable record across hospitals. */
    private static final Pattern CROSS_HOSPITAL_READ = Pattern.compile(
        "\\b(?:prescriptionRepository|imagingOrderRepository|referralRepository)\\s*+\\.\\s*+"
            + "find\\w*Hospital_?IdIn\\w*\\s*+\\(");
    /** Class -> why its cross-hospital read needs no NeverSentRule. Only shrinks. */
    private static final Map<String, String> RECORDED = Map.of(
        "ObgynReferralServiceImpl.java", "an OB/GYN referral has no never-sent state: it is SUBMITTED on creation"
            + " (ObgynReferralStatus has no DRAFT)");

    private static final Pattern NEVER_SENT_CALL = Pattern.compile("\\bNeverSentRule\\s*+\\.\\s*+\\w++\\s*+\\(");

    @Test
    @DisplayName("every re-tested patient list still has its by-id read, and both apply NeverSentRule where it applies")
    void everyPatientListAgreesWithItsByIdRead() throws IOException {
        for (ListPair pair : PATIENT_LISTS) {
            String controller = read(findFile(pair.controller() + ".java"));
            assertThat(controller).as(pair.controller() + " list mapping").contains(pair.listMapping());
            assertThat(controller).as(pair.controller() + " by-id mapping").contains("\"" + pair.byIdMapping() + "\"");
            String service = read(MAIN.resolve(pair.service()));
            if (pair.neverSentState()) {
                assertThat(NEVER_SENT_CALL.matcher(service).results().count())
                    .as(pair.service() + ": the list AND the by-id read must both ask NeverSentRule")
                    .isGreaterThanOrEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("every cross-hospital read of a prescription, imaging order or referral applies NeverSentRule")
    void everyCrossHospitalReadAppliesTheRule() throws IOException {
        Set<String> readers = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = read(file);
                if (!CROSS_HOSPITAL_READ.matcher(src).find()) {
                    continue;
                }
                String name = file.getFileName().toString();
                readers.add(name);
                if (!RECORDED.containsKey(name) && !NEVER_SENT_CALL.matcher(src).find()) {
                    missing.add(name);
                }
            }
        }
        assertThat(readers).as("the scan must find the known cross-hospital readers, or it scans nothing")
            .contains("PrescriptionServiceImpl.java", "ImagingOrderServiceImpl.java", "GeneralReferralServiceImpl.java",
                "PatientServiceImpl.java", "ChartReviewServiceImpl.java", "PatientEverythingService.java");
        assertThat(missing)
            .as("classes reading prescriptions, imaging orders or referrals across readableHospitalIds without"
                + " NeverSentRule: another hospital's DRAFT would show here and 404 by id (QA D652)")
            .isEmpty();
    }

    @Test
    @DisplayName("the rule: a never-sent row is readable only at its own hospital (or in global view)")
    void theRule() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(NeverSentRule.readableHere(a, b, false)).isFalse();
        assertThat(NeverSentRule.readableHere(a, b, true)).isTrue();
        assertThat(NeverSentRule.readableHere(a, a, false)).isTrue();
        assertThat(NeverSentRule.readableHere(a, null, false)).isTrue();
        assertThat(NeverSentRule.sent(PrescriptionStatus.DRAFT)).isFalse();
        assertThat(NeverSentRule.sent(PrescriptionStatus.PENDING_SIGNATURE)).isFalse();
        assertThat(NeverSentRule.sent(PrescriptionStatus.SIGNED)).isTrue();
        assertThat(NeverSentRule.sent(ImagingOrderStatus.DRAFT)).isFalse();
        assertThat(NeverSentRule.sent(ImagingOrderStatus.ORDERED)).isTrue();

        Hospital hospitalA = new Hospital();
        hospitalA.setId(a);
        Prescription draftAtA = new Prescription();
        draftAtA.setHospital(hospitalA);
        draftAtA.setStatus(PrescriptionStatus.DRAFT);
        assertThat(NeverSentRule.prescriptionsReadableHere(List.of(draftAtA), b)).isEmpty();
        assertThat(NeverSentRule.prescriptionsReadableHere(List.of(draftAtA), a)).containsExactly(draftAtA);
    }

    private static Path findFile(String name) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.getFileName().toString().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not found"));
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
