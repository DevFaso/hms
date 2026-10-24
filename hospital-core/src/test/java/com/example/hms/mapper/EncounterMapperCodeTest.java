package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.EncounterResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D276: the encounter response carries the human-readable encounter code. */
class EncounterMapperCodeTest {

    @Test
    void responseExposesTheEncounterCode() {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setCode("ENC-2026-000123");

        EncounterResponseDTO dto = new EncounterMapper().toEncounterResponseDTO(e);

        assertThat(dto.getCode()).isEqualTo("ENC-2026-000123");
    }

    @Test
    void d759_theAnchorsEnglishBookkeepingNoteIsNotShownAsIs() {
        Encounter anchor = new Encounter();
        anchor.setId(UUID.randomUUID());
        anchor.setNotes(Encounter.PRESCRIPTION_ANCHOR_NOTE);
        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setNotes("Toux depuis 3 jours");

        assertThat(new EncounterMapper().toEncounterResponseDTO(anchor).getNotes())
            .isNotEqualTo(Encounter.PRESCRIPTION_ANCHOR_NOTE);
        // Path re-verified: a clinician's own note is untouched.
        assertThat(new EncounterMapper().toEncounterResponseDTO(visit).getNotes()).isEqualTo("Toux depuis 3 jours");
    }
}
