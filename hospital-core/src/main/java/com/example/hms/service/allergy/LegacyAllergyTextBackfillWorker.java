package com.example.hms.service.allergy;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * One patient of the legacy free-text allergy backfill (E9 #56), in its own
 * transaction so a failure on one row never rolls back the others. Kept as
 * a separate bean because {@code REQUIRES_NEW} on a self-invoked method is
 * a no-op — Spring's transaction proxy is invocation-time.
 */
@Component
@RequiredArgsConstructor
public class LegacyAllergyTextBackfillWorker {

    public enum Outcome {
        /** Structured rows were created from the free text. */
        IMPORTED,
        /** The patient already carries rows from a free-text import; nothing to do. */
        ALREADY_DONE,
        /** The text is blank or only states that there are no allergies; left as is. */
        NOTHING_TO_IMPORT,
        /** The patient has no hospital to attach the rows to (the column is NOT NULL). */
        NO_HOSPITAL,
        /** No patient with that id. */
        MISSING
    }

    private final PatientRepository patientRepository;
    private final PatientAllergyRepository allergyRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final LegacyAllergyTextImporter importer;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome backfillOne(UUID patientId) {
        Patient patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null) {
            return Outcome.MISSING;
        }
        String text = patient.getAllergies();
        if (text == null || text.isBlank()) {
            return Outcome.NOTHING_TO_IMPORT;
        }
        // Idempotency: a patient whose text has already been turned into rows
        // (by this backfill, or by the registration importer at create time,
        // after which the column holds the derived summary) is skipped.
        if (allergyRepository.existsByPatient_IdAndSourceSystem(patientId, LegacyAllergyTextImporter.SOURCE_LEGACY)
            || allergyRepository.existsByPatient_IdAndSourceSystem(patientId, LegacyAllergyTextImporter.SOURCE_REGISTRATION)) {
            return Outcome.ALREADY_DONE;
        }
        if (LegacyAllergyText.tokens(text).isEmpty()) {
            // «Aucune allergie connue» stays as the clerk wrote it: it is a
            // statement, not an allergen, and there is no row to make of it.
            return Outcome.NOTHING_TO_IMPORT;
        }
        Hospital hospital = resolveHospital(patient);
        if (hospital == null) {
            return Outcome.NO_HOSPITAL;
        }
        importer.importFreeText(patient, hospital, null, text, LegacyAllergyTextImporter.SOURCE_LEGACY);
        return Outcome.IMPORTED;
    }

    /** The patient's first hospital, else the first hospital they are registered at. */
    private Hospital resolveHospital(Patient patient) {
        if (patient.getHospitalId() != null) {
            Hospital byColumn = hospitalRepository.findById(patient.getHospitalId()).orElse(null);
            if (byColumn != null) {
                return byColumn;
            }
        }
        return registrationRepository.findByPatientId(patient.getId()).stream()
            .map(PatientHospitalRegistration::getHospital)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}
