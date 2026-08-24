package com.example.hms.service.emar;

import com.example.hms.utility.ElapsedTime;
import com.example.hms.enums.FiveRightsCheck;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side enforcement of the bedside five-rights barcode-scan loop on the
 * inpatient eMAR (P1 #8).
 *
 * <p>The nurse scans the patient wristband and the medication label, then
 * confirms dose / route / time. The eMAR UI never decides whether the dose may
 * proceed — this service is the source of truth. The frontend mirrors the
 * checklist purely for display.
 *
 * <p>Each check is conservative: missing values fail rather than pass-by-default
 * so a malformed scan never silently certifies a right. The matching rules
 * tolerate whitespace and case differences but not semantic differences.
 */
@Service
public class FiveRightsVerificationService {

    /** Default ±60 min window around the scheduled administration time. */
    public static final Duration DEFAULT_TIME_WINDOW = Duration.ofMinutes(60);

    /**
     * Verify the supplied bedside scan values against the prescription bound
     * to the MAR record. The result is purely advisory — the caller decides
     * whether to persist a {@code VERIFIED} or {@code OVERRIDDEN} status based
     * on the outcomes and any override reason.
     */
    public FiveRightsVerificationResult verify(
        MedicationAdministrationRecord mar,
        String patientScan,
        String medicationScan,
        String doseScan,
        String routeScan,
        LocalDateTime administeredAt
    ) {
        Objects.requireNonNull(mar, "mar");
        Prescription rx = mar.getPrescription();
        FiveRightsVerificationResult.Builder b = FiveRightsVerificationResult.builder();

        verifyPatient(mar.getPatient(), patientScan, b);
        verifyDrug(rx, medicationScan, b);
        verifyDose(rx, doseScan, b);
        verifyRoute(rx, routeScan, b);
        verifyTime(mar.getScheduledTime(), administeredAt, b);

        return b.build();
    }

    /** Right patient: the scanned wristband must resolve to the prescription's patient UUID. */
    private void verifyPatient(Patient patient, String scan, FiveRightsVerificationResult.Builder b) {
        if (isBlank(scan)) {
            b.fail(FiveRightsCheck.PATIENT, "patient wristband was not scanned");
            return;
        }
        if (patient == null || patient.getId() == null) {
            b.fail(FiveRightsCheck.PATIENT, "MAR record has no patient");
            return;
        }
        UUID scanned = parseUuidOrNull(scan);
        if (scanned == null) {
            b.fail(FiveRightsCheck.PATIENT, "scanned wristband value is not a patient identifier");
            return;
        }
        if (patient.getId().equals(scanned)) {
            b.pass(FiveRightsCheck.PATIENT);
        } else {
            b.fail(FiveRightsCheck.PATIENT, "scanned patient does not match the prescribed patient");
        }
    }

    /**
     * Right drug: prefer matching on {@code medication_code} (formulary or
     * RxNorm). Fall back to a case-insensitive match on the medication name
     * when no code is recorded on the prescription.
     */
    private void verifyDrug(Prescription rx, String scan, FiveRightsVerificationResult.Builder b) {
        if (isBlank(scan)) {
            b.fail(FiveRightsCheck.DRUG, "medication barcode was not scanned");
            return;
        }
        if (rx == null) {
            b.fail(FiveRightsCheck.DRUG, "MAR record has no prescription");
            return;
        }
        String normalized = normalize(scan);
        String code = normalize(rx.getMedicationCode());
        if (!isBlank(code)) {
            if (code.equals(normalized)) {
                b.pass(FiveRightsCheck.DRUG);
            } else {
                b.fail(FiveRightsCheck.DRUG, "scanned medication code does not match prescribed code");
            }
            return;
        }
        String name = normalize(rx.getMedicationName());
        if (!isBlank(name) && name.equals(normalized)) {
            b.pass(FiveRightsCheck.DRUG);
        } else {
            b.fail(FiveRightsCheck.DRUG, "scanned medication does not match prescribed medication");
        }
    }

    /** Right dose: the scanned dose token must match the prescription's dosage. */
    private void verifyDose(Prescription rx, String scan, FiveRightsVerificationResult.Builder b) {
        if (isBlank(scan)) {
            b.fail(FiveRightsCheck.DOSE, "dose was not entered");
            return;
        }
        if (rx == null || isBlank(rx.getDosage())) {
            b.fail(FiveRightsCheck.DOSE, "prescription does not record a dose");
            return;
        }
        if (normalize(rx.getDosage()).equals(normalize(scan))) {
            b.pass(FiveRightsCheck.DOSE);
        } else {
            b.fail(FiveRightsCheck.DOSE, "entered dose does not match prescribed dose");
        }
    }

    /** Right route: scanned route must match the prescription. */
    private void verifyRoute(Prescription rx, String scan, FiveRightsVerificationResult.Builder b) {
        if (isBlank(scan)) {
            b.fail(FiveRightsCheck.ROUTE, "route was not entered");
            return;
        }
        if (rx == null || isBlank(rx.getRoute())) {
            b.fail(FiveRightsCheck.ROUTE, "prescription does not record a route");
            return;
        }
        if (normalize(rx.getRoute()).equals(normalize(scan))) {
            b.pass(FiveRightsCheck.ROUTE);
        } else {
            b.fail(FiveRightsCheck.ROUTE, "entered route does not match prescribed route");
        }
    }

    /** Right time: administration must fall inside the configured window. */
    private void verifyTime(
        LocalDateTime scheduledTime, LocalDateTime administeredAt,
        FiveRightsVerificationResult.Builder b
    ) {
        if (scheduledTime == null) {
            b.fail(FiveRightsCheck.TIME, "MAR record has no scheduled time");
            return;
        }
        LocalDateTime when = administeredAt != null ? administeredAt : LocalDateTime.now();
        Duration delta = ElapsedTime.between(scheduledTime, when).abs();
        if (delta.compareTo(DEFAULT_TIME_WINDOW) <= 0) {
            b.pass(FiveRightsCheck.TIME);
        } else {
            b.fail(FiveRightsCheck.TIME,
                "administration is outside the " + DEFAULT_TIME_WINDOW.toMinutes() + "-minute window");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuidOrNull(String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
