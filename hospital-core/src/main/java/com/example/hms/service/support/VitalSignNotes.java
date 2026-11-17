package com.example.hms.service.support;

import com.example.hms.model.PatientVitalSign;
import com.example.hms.utility.MessageUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * QA D1169 (round 21): the pain score and the fundal height of a vitals row
 * were stored as a sentence in the nurse's language ("Douleur : 8/10"), so
 * Spanish and English readers read French. They are numbers now
 * ({@code pain_score}, {@code fundal_height_cm}, V257), and this composes the
 * notes a reader sees, in the reader's language, at read time: the score, the
 * height, then what the nurse typed.
 */
public final class VitalSignNotes {

    private static final String SEPARATOR = " · ";

    private VitalSignNotes() {
    }

    /** The notes shown for a vitals row, or {@code null} when there is nothing to show. */
    public static String display(PatientVitalSign vital) {
        if (vital == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (vital.getPainScore() != null) {
            parts.add(MessageUtil.resolveOrRaw("triage.vitals.painScale", vital.getPainScore()));
        }
        if (vital.getFundalHeightCm() != null) {
            parts.add(MessageUtil.resolveOrRaw("triage.vitals.fundalHeight", vital.getFundalHeightCm()));
        }
        if (vital.getNotes() != null && !vital.getNotes().isBlank()) {
            parts.add(vital.getNotes().strip());
        }
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }
}
