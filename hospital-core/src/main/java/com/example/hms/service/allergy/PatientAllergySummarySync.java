package com.example.hms.service.allergy;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps {@code patients.allergies} equal to the patient's ACTIVE structured
 * allergies (E9 #56).
 *
 * <p>Before this there were three allergy stores that nothing kept in step:
 * the free-text column on the patient row (read by the Medical tab, the
 * nurse dashboard flag, the patient portal and the snapshot), the structured
 * {@code patient_allergies} table (read by the storyboard, the chart tab and
 * the timeline), and the maternal-history text. A nurse saw "no known
 * allergies" on the storyboard beside "peanuts, garlic" on the Medical tab
 * of the same patient.
 *
 * <p>The structured table is now the only store that is written by hand. The
 * free-text column is a derived summary — rewritten here after every
 * structured write and by the one-shot legacy backfill — so every reader of
 * the column shows the same allergies the chart does, without each of them
 * having to change.
 */
@Service
@RequiredArgsConstructor
public class PatientAllergySummarySync {

    /** Column width of {@code patients.allergies} (the {@code @Size} on the entity). */
    static final int MAX_SUMMARY = 2048;

    private static final String SEPARATOR = ", ";

    private final PatientAllergyRepository allergyRepository;
    private final PatientRepository patientRepository;

    /**
     * Rewrite the patient's free-text summary from their active structured
     * allergies. Saves only when the summary actually changed.
     *
     * @return the summary now on the patient, {@code null} when there is no
     *         active structured allergy
     */
    @Transactional
    public String refresh(Patient patient) {
        Objects.requireNonNull(patient, "patient");
        String summary = summarize(allergyRepository.findByPatient_Id(patient.getId()));
        if (!Objects.equals(summary, patient.getAllergies())) {
            patient.setAllergies(summary);
            patientRepository.save(patient);
        }
        return summary;
    }

    /**
     * Active allergen names, most severe first, de-duplicated
     * case-insensitively, joined for display. {@code null} when none.
     */
    public static String summarize(List<PatientAllergy> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, PatientAllergy> distinct = new LinkedHashMap<>();
        rows.stream()
            .filter(PatientAllergy::isActive)
            .filter(a -> a.getAllergenDisplay() != null && !a.getAllergenDisplay().isBlank())
            .sorted(Comparator.comparingInt(PatientAllergySummarySync::severityRank).reversed()
                .thenComparing(PatientAllergy::getAllergenDisplay, String.CASE_INSENSITIVE_ORDER))
            .forEach(a -> distinct.putIfAbsent(LegacyAllergyText.normalise(a.getAllergenDisplay()), a));
        if (distinct.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (PatientAllergy a : distinct.values()) {
            String display = a.getAllergenDisplay().trim();
            int next = out.length() + (out.isEmpty() ? 0 : SEPARATOR.length()) + display.length();
            if (next > MAX_SUMMARY) {
                break;
            }
            if (!out.isEmpty()) {
                out.append(SEPARATOR);
            }
            out.append(display);
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static int severityRank(PatientAllergy a) {
        AllergySeverity s = a.getSeverity();
        if (s == null) {
            return 0;
        }
        return switch (s) {
            case LIFE_THREATENING -> 4;
            case SEVERE -> 3;
            case MODERATE -> 2;
            case MILD -> 1;
            default -> 0;
        };
    }
}
