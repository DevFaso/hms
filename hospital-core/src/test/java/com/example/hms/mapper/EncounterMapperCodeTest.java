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
    void d831_thePatientNeverReceivesTheStaysEntriesNorClinicianIds() {
        // #965 review: GET /me/patient/encounters returned every entry of the
        // stay, another doctor's unsigned draft included, with user ids.
        com.example.hms.model.User doctorA = new com.example.hms.model.User();
        doctorA.setId(UUID.randomUUID());
        com.example.hms.model.User doctor2 = new com.example.hms.model.User();
        doctor2.setId(UUID.randomUUID());
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setAdmissionId(UUID.randomUUID());
        com.example.hms.model.encounter.EncounterNote j0 = com.example.hms.model.encounter.EncounterNote.builder()
            .author(doctorA).assessment("J0 brouillon non signé").build();
        j0.setId(UUID.randomUUID());
        j0.setCreatedAt(java.time.LocalDateTime.now().minusHours(4));
        com.example.hms.model.encounter.EncounterNote j1 = com.example.hms.model.encounter.EncounterNote.builder()
            .author(doctor2).assessment("J1 signé").signedAt(java.time.LocalDateTime.now())
            .signedByUserId(doctor2.getId()).build();
        j1.setId(UUID.randomUUID());
        j1.setCreatedAt(java.time.LocalDateTime.now());
        stay.setEncounterNote(j0);
        stay.setEncounterNote(j1);
        EncounterMapper mapper = new EncounterMapper();

        // Path re-verified: the clinician still reads both entries.
        assertThat(mapper.toEncounterResponseDTO(stay).getNoteEntries()).hasSize(2);

        EncounterResponseDTO portal = mapper.toPatientPortalEncounterResponseDTO(stay);
        assertThat(portal.getNoteEntries()).isNull();
        assertThat(portal.getNote().getId()).isEqualTo(j1.getId());
        assertThat(portal.getNote().getAuthorUserId()).isNull();
        assertThat(portal.getNote().getSignedByUserId()).isNull();
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
