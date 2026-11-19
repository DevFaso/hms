package com.example.hms.service.support;

import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteAddendum;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.model.encounter.EncounterNoteAddendum;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.NoteAddendumEntryDTO;
import com.example.hms.utility.JobTitleCredentials;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * QA D2260 (r63): a signed note is locked, and a correction to it is an addendum. The addendum was saved
 * but no reader showed it: not the visit, the chart review, the record PDF or the after-visit summary, so
 * a late correction (a lab value that changed the assessment) was invisible to everyone who read the
 * record. Every reader of a note takes its addenda from here, oldest first, each with its author and
 * dates ({@code SignedNoteAddendaGuardTest}).
 */
public final class NoteAddenda {

    private static final Comparator<NoteAddendumEntryDTO> OLDEST_FIRST = Comparator
        .comparing(NoteAddenda::when, Comparator.nullsLast(Comparator.naturalOrder()));

    private NoteAddenda() {
    }

    /** The addenda of a visit's clinical note, oldest first; empty when it has none. */
    public static List<NoteAddendumEntryDTO> of(EncounterNote note) {
        if (note == null || note.getAddenda() == null || note.getAddenda().isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return note.getAddenda().stream()
            .filter(a -> a != null && a.getContent() != null && !a.getContent().isBlank())
            .map(NoteAddenda::entry)
            .sorted(OLDEST_FIRST)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * The addenda a PATIENT COPY may carry: only those their author signed (attested). An addendum saved
     * without the accuracy attestation has no {@code signedAt}; staff readers show it as "not attested",
     * the patient never receives it. Every patient-copy reader calls this, never {@link #of(EncounterNote)}
     * ({@code SignedNoteAddendaGuardTest}).
     */
    public static List<NoteAddendumEntryDTO> signedOnly(EncounterNote note) {
        return of(note).stream()
            .filter(a -> a.getSignedAt() != null)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /** The addenda of a ward nursing note, oldest first; empty when it has none. */
    public static List<NoteAddendumEntryDTO> of(NursingNote note) {
        if (note == null || note.getAddenda() == null || note.getAddenda().isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return note.getAddenda().stream()
            .filter(a -> a != null && a.getContent() != null && !a.getContent().isBlank())
            .map(NoteAddenda::entry)
            .sorted(OLDEST_FIRST)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    private static NoteAddendumEntryDTO entry(EncounterNoteAddendum a) {
        return NoteAddendumEntryDTO.builder()
            .id(a.getId())
            .content(a.getContent().strip())
            .authorName(a.getAuthorName())
            .authorCredentials(JobTitleCredentials.toCode(a.getAuthorCredentials()))
            .documentedAt(a.getDocumentedAt() != null ? a.getDocumentedAt() : a.getCreatedAt())
            .signedAt(a.getSignedAt())
            .build();
    }

    private static NoteAddendumEntryDTO entry(NursingNoteAddendum a) {
        return NoteAddendumEntryDTO.builder()
            .id(a.getId())
            .content(a.getContent().strip())
            .authorName(a.getAuthorName())
            .authorCredentials(JobTitleCredentials.toCode(a.getAuthorCredentials()))
            .documentedAt(a.getDocumentedAt() != null ? a.getDocumentedAt() : a.getCreatedAt())
            .signedAt(a.getSignedAt())
            .build();
    }

    /** When the addendum was signed, else documented: what a reader dates it by. */
    public static LocalDateTime when(NoteAddendumEntryDTO a) {
        return a.getSignedAt() != null ? a.getSignedAt() : a.getDocumentedAt();
    }
}
