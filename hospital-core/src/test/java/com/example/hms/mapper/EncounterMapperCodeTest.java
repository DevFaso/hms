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
}
