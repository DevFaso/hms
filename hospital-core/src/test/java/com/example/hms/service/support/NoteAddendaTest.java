package com.example.hms.service.support;

import com.example.hms.model.NursingNote;
import com.example.hms.model.NursingNoteAddendum;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.model.encounter.EncounterNoteAddendum;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO.NoteAddendumEntryDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D2260 (r63): every note reader takes a note's addenda from here, oldest first. */
class NoteAddendaTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 19, 4, 0);

    @Test
    void aNoteWithoutAddendaHasNone() {
        assertThat(NoteAddenda.of((EncounterNote) null)).isEmpty();
        assertThat(NoteAddenda.of((NursingNote) null)).isEmpty();
        assertThat(NoteAddenda.of(new EncounterNote())).isEmpty();
        assertThat(NoteAddenda.of(new NursingNote())).isEmpty();
    }

    @Test
    void aNursingNotesAddendaAreOldestFirstAndBlankOnesLeftOut() {
        NursingNote note = new NursingNote();
        NursingNoteAddendum later = new NursingNoteAddendum();
        later.setContent("  later  ");
        later.setAuthorName("Inf. B");
        later.setSignedAt(T.plusHours(2));
        NursingNoteAddendum earlier = new NursingNoteAddendum();
        earlier.setContent("earlier");
        earlier.setAuthorName("Inf. A");
        earlier.setDocumentedAt(T.plusHours(1));
        NursingNoteAddendum blank = new NursingNoteAddendum();
        blank.setContent(" ");
        note.addAddendum(later);
        note.addAddendum(earlier);
        note.addAddendum(blank);

        assertThat(NoteAddenda.of(note)).extracting(NoteAddendumEntryDTO::getContent)
            .containsExactly("earlier", "later");
        assertThat(NoteAddenda.of(note).get(0).getSignedAt()).isNull();
        assertThat(NoteAddenda.when(NoteAddenda.of(note).get(1))).isEqualTo(T.plusHours(2));
    }

    @Test
    void anUndatedAddendumIsDatedByItsCreationAndSortsLast() {
        EncounterNote note = new EncounterNote();
        EncounterNoteAddendum undated = new EncounterNoteAddendum();
        undated.setContent("undated");
        EncounterNoteAddendum dated = new EncounterNoteAddendum();
        dated.setContent("dated");
        dated.setDocumentedAt(T);
        note.addAddendum(undated);
        note.addAddendum(dated);

        assertThat(NoteAddenda.of(note)).extracting(NoteAddendumEntryDTO::getContent)
            .containsExactly("dated", "undated");
    }
}
