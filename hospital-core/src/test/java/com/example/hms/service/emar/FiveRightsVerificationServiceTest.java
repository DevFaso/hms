package com.example.hms.service.emar;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.FiveRightsCheck;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

class FiveRightsVerificationServiceTest {

    private final FiveRightsVerificationService service = new FiveRightsVerificationService();

    private static final UUID PATIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void allRightsPassWhenScansMatchPrescription() {
        MedicationAdministrationRecord mar = mar(scheduled(LocalDateTime.now()));

        FiveRightsVerificationResult result = service.verify(
            mar,
            PATIENT_ID.toString(),
            "AMOX-500",
            "500 mg",
            "PO",
            LocalDateTime.now()
        );

        assertThat(result.allPassed()).isTrue();
        assertThat(result.failedChecks()).isEmpty();
    }

    @Test
    void patientFailsWhenWristbandScanIsBlank() {
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled(LocalDateTime.now())),
            null, "AMOX-500", "500 mg", "PO", LocalDateTime.now()
        );

        assertThat(result.failedChecks()).contains(FiveRightsCheck.PATIENT);
        assertThat(result.getFailureReasons().get(FiveRightsCheck.PATIENT)).contains("not scanned");
    }

    @Test
    void patientFailsWhenWristbandUuidIsForOtherPatient() {
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled(LocalDateTime.now())),
            UUID.randomUUID().toString(), "AMOX-500", "500 mg", "PO", LocalDateTime.now()
        );

        assertThat(result.failedChecks()).contains(FiveRightsCheck.PATIENT);
    }

    @Test
    void patientFailsWhenScanIsNotAUuid() {
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled(LocalDateTime.now())),
            "MRN-123", "AMOX-500", "500 mg", "PO", LocalDateTime.now()
        );

        assertThat(result.failedChecks()).contains(FiveRightsCheck.PATIENT);
    }

    @Test
    void drugFailsWhenScanCodeMismatchesPrescriptionCode() {
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled(LocalDateTime.now())),
            PATIENT_ID.toString(), "WRONG-CODE", "500 mg", "PO", LocalDateTime.now()
        );

        assertThat(result.failedChecks()).contains(FiveRightsCheck.DRUG);
    }

    @Test
    void drugMatchesByMedicationNameWhenCodeIsAbsent() {
        Prescription rx = prescription();
        rx.setMedicationCode(null);
        rx.setMedicationName("Amoxicillin 500 mg");
        MedicationAdministrationRecord mar = mar(scheduled(LocalDateTime.now()));
        mar.setPrescription(rx);

        FiveRightsVerificationResult result = service.verify(
            mar,
            PATIENT_ID.toString(), " amoxicillin 500 MG ", "500 mg", "PO", LocalDateTime.now()
        );

        assertThat(result.getOutcomes().get(FiveRightsCheck.DRUG)).isTrue();
    }

    @Test
    void doseFailsWhenScanDoesNotMatchPrescription() {
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled(LocalDateTime.now())),
            PATIENT_ID.toString(), "AMOX-500", "1000 mg", "PO", LocalDateTime.now()
        );

        assertThat(result.failedChecks()).contains(FiveRightsCheck.DOSE);
    }

    @Test
    void routeFailsWhenScanDoesNotMatchPrescription() {
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled(LocalDateTime.now())),
            PATIENT_ID.toString(), "AMOX-500", "500 mg", "IV", LocalDateTime.now()
        );

        assertThat(result.failedChecks()).contains(FiveRightsCheck.ROUTE);
    }

    @Test
    void timeFailsWhenAdministrationIsOutsideWindow() {
        LocalDateTime scheduled = LocalDateTime.now();
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled),
            PATIENT_ID.toString(), "AMOX-500", "500 mg", "PO", scheduled.plusHours(3)
        );

        assertThat(result.failedChecks()).contains(FiveRightsCheck.TIME);
        assertThat(result.getFailureReasons().get(FiveRightsCheck.TIME)).contains("window");
    }

    @Test
    void timePassesWhenAdministrationIsInsideWindow() {
        LocalDateTime scheduled = LocalDateTime.now();
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled),
            PATIENT_ID.toString(), "AMOX-500", "500 mg", "PO", scheduled.plusMinutes(45)
        );

        assertThat(result.getOutcomes().get(FiveRightsCheck.TIME)).isTrue();
    }

    @Test
    void verificationIsCaseInsensitiveAndTrimsWhitespace() {
        FiveRightsVerificationResult result = service.verify(
            mar(scheduled(LocalDateTime.now())),
            PATIENT_ID.toString(), "amox-500", "500 MG", "po", LocalDateTime.now()
        );

        assertThat(result.allPassed()).isTrue();
    }

    /* ── Test fixtures ─────────────────────────────────────────────────── */

    private LocalDateTime scheduled(LocalDateTime now) {
        return now;
    }

    private Patient patient() {
        Patient p = new Patient();
        p.setId(PATIENT_ID);
        return p;
    }

    private Prescription prescription() {
        Prescription rx = new Prescription();
        rx.setMedicationName("Amoxicillin");
        rx.setMedicationCode("AMOX-500");
        rx.setDosage("500 mg");
        rx.setRoute("PO");
        return rx;
    }

    private MedicationAdministrationRecord mar(LocalDateTime scheduledTime) {
        MedicationAdministrationRecord r = new MedicationAdministrationRecord();
        r.setPatient(patient());
        r.setPrescription(prescription());
        r.setScheduledTime(scheduledTime);
        return r;
    }
}
