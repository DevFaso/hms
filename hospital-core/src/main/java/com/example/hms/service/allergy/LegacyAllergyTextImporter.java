package com.example.hms.service.allergy;

import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Staff;
import com.example.hms.repository.PatientAllergyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Creates structured {@link PatientAllergy} rows from free text (E9 #56):
 * the registration form at create time, and the one-shot backfill of the
 * rows written before the structured table became the only store.
 *
 * <p>Every row is {@code UNCONFIRMED}, carries the source it came from, and
 * keeps the original text in its notes so nothing the clerk typed is lost
 * when the summary column is rewritten. Names already present as active
 * rows are skipped, case-insensitively, so re-importing the same text (or
 * text that repeats what a nurse already recorded) never duplicates.
 */
@Service
@RequiredArgsConstructor
public class LegacyAllergyTextImporter {

    /** Rows created from the registration form's free-text field at create time. */
    public static final String SOURCE_REGISTRATION = "REGISTRATION_FREE_TEXT";

    /** Rows created by the startup backfill from the pre-#56 free-text column. */
    public static final String SOURCE_LEGACY = "LEGACY_FREE_TEXT";

    /** Column width of {@code patient_allergies.reaction_notes}. */
    static final int MAX_NOTES = 1024;

    private static final String NOTES_PREFIX = "Importé du texte libre : ";

    private final PatientAllergyRepository allergyRepository;
    private final PatientAllergySummarySync summarySync;
    private final Clock clock;

    /**
     * Import the allergen names in {@code text} as structured rows for
     * {@code patient} at {@code hospital}, then rewrite the patient's summary.
     *
     * @param recordedBy the staff member entering the text, or {@code null}
     *                   for the backfill (no person entered it today)
     * @return the number of rows created
     */
    @Transactional
    public int importFreeText(Patient patient, Hospital hospital, Staff recordedBy, String text, String sourceSystem) {
        Objects.requireNonNull(patient, "patient");
        Objects.requireNonNull(hospital, "hospital");
        List<String> tokens = LegacyAllergyText.tokens(text);
        if (tokens.isEmpty()) {
            return 0;
        }
        Set<String> present = new HashSet<>();
        for (PatientAllergy existing : allergyRepository.findByPatient_Id(patient.getId())) {
            if (existing.isActive() && existing.getAllergenDisplay() != null) {
                present.add(LegacyAllergyText.normalise(existing.getAllergenDisplay()));
            }
        }
        String notes = notesFor(text);
        LocalDate today = LocalDate.now(clock);
        int created = 0;
        for (String token : tokens) {
            if (!present.add(LegacyAllergyText.normalise(token))) {
                continue;
            }
            allergyRepository.save(PatientAllergy.builder()
                .patient(patient)
                .hospital(hospital)
                .recordedBy(recordedBy)
                .allergenDisplay(token)
                .verificationStatus(AllergyVerificationStatus.UNCONFIRMED)
                .reactionNotes(notes)
                .recordedDate(today)
                .sourceSystem(sourceSystem)
                .active(true)
                .build());
            created++;
        }
        summarySync.refresh(patient);
        return created;
    }

    private static String notesFor(String text) {
        String trimmed = text.trim();
        int room = MAX_NOTES - NOTES_PREFIX.length();
        if (trimmed.length() > room) {
            trimmed = trimmed.substring(0, room - 1) + "…";
        }
        return NOTES_PREFIX + trimmed;
    }
}
