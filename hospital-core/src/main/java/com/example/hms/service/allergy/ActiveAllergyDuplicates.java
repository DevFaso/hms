package com.example.hms.service.allergy;

import com.example.hms.model.PatientAllergy;
import com.example.hms.utility.PatientMatchKeys;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D1287 (round 27): the same allergy could be recorded twice as active
 * (the nursing intake and {@code POST /patients/{id}/allergies}), and the
 * banner showed it twice. One definition of "the same allergen" for every
 * path that makes an allergy active: the same coded identity (catalogue
 * code, case and spacing aside) or the same typed name (accents, case and
 * spacing aside). A deactivated allergy does not count, so it may be
 * recorded again.
 */
public final class ActiveAllergyDuplicates {

    private ActiveAllergyDuplicates() {
    }

    /**
     * The active allergy among {@code visible} (the rows the acting hospital
     * may see, so nothing hidden by a sharing opt-out is revealed) that names
     * the same allergen as {@code code} / {@code display}, other than
     * {@code self} (the row being edited, or null).
     */
    public static Optional<PatientAllergy> find(Collection<PatientAllergy> visible, String code, String display,
                                                UUID self) {
        if (visible == null || visible.isEmpty()) {
            return Optional.empty();
        }
        String codeKey = codeKey(code);
        String nameKey = PatientMatchKeys.foldName(display);
        if (codeKey == null && nameKey == null) {
            return Optional.empty();
        }
        return visible.stream()
            .filter(PatientAllergy::isActive)
            .filter(a -> self == null || !self.equals(a.getId()))
            .filter(a -> (codeKey != null && codeKey.equals(codeKey(a.getAllergenCode())))
                || (nameKey != null && nameKey.equals(PatientMatchKeys.foldName(a.getAllergenDisplay()))))
            .findFirst();
    }

    private static String codeKey(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
