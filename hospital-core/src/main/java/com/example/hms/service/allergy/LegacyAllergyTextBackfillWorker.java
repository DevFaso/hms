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
        /** Structured rows were created from the free text (at least one). */
        IMPORTED,
        /**
         * QA D2220: the text names only allergens the chart already has
         * (active, deactivated or entered in error); no row was created.
         */
        NOTHING_NEW,
        /** Already processed (ledger), or carries rows from a free-text import; nothing to do. */
        ALREADY_DONE,
        /** The text is blank or only states that there are no allergies; left as is. */
        NOTHING_TO_IMPORT,
        /** The patient has no hospital to attach the rows to (the column is NOT NULL); retried next start. */
        NO_HOSPITAL,
        /** No patient with that id. */
        MISSING
    }

    private final PatientRepository patientRepository;
    private final PatientAllergyRepository allergyRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final LegacyAllergyTextImporter importer;
    private final LegacyAllergyImportLedger ledger;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome backfillOne(UUID patientId) {
        Patient patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null) {
            return Outcome.MISSING;
        }
        // QA D2220: done-ness is recorded, never inferred. A patient the
        // ledger knows is never processed again, whatever the column holds
        // now: the column is a derived summary after the first pass, and a
        // later deactivation must not be undone by the next start.
        if (ledger.isDone(patientId)) {
            return Outcome.ALREADY_DONE;
        }
        String text = patient.getAllergies();
        if (text == null || text.isBlank()) {
            return mark(patientId, Outcome.NOTHING_TO_IMPORT, 0);
        }
        // A patient whose text was already turned into rows before the ledger
        // existed (by this backfill, or by the registration importer at create
        // time) is recorded and skipped.
        if (allergyRepository.existsByPatient_IdAndSourceSystem(patientId, LegacyAllergyTextImporter.SOURCE_LEGACY)
            || allergyRepository.existsByPatient_IdAndSourceSystem(patientId, LegacyAllergyTextImporter.SOURCE_REGISTRATION)) {
            return mark(patientId, Outcome.ALREADY_DONE, 0);
        }
        if (LegacyAllergyText.tokens(text).isEmpty()) {
            // «Aucune allergie connue» stays as the clerk wrote it: it is a
            // statement, not an allergen, and there is no row to make of it.
            return mark(patientId, Outcome.NOTHING_TO_IMPORT, 0);
        }
        Hospital hospital = resolveHospital(patient);
        if (hospital == null) {
            // Not marked: the patient may gain a hospital, and is retried then.
            return Outcome.NO_HOSPITAL;
        }
        int created = importer.importFreeText(patient, hospital, null, text, LegacyAllergyTextImporter.SOURCE_LEGACY);
        return mark(patientId, created > 0 ? Outcome.IMPORTED : Outcome.NOTHING_NEW, created);
    }

    /** Record the outcome in the ledger, in this patient's transaction, and return it. */
    private Outcome mark(UUID patientId, Outcome outcome, int created) {
        ledger.markDone(patientId, outcome, created);
        return outcome;
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
