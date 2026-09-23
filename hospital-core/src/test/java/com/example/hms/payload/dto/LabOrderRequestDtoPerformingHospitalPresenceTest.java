package com.example.hms.payload.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1: {@code performingHospitalId} has three request states, and the two that
 * look alike in Java must not look alike over the wire — an absent field
 * leaves an order's routing untouched, an explicit {@code null} brings the
 * test back in-house. Collapsing them means an outsourced order can never
 * come home.
 */
class LabOrderRequestDtoPerformingHospitalPresenceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void absentFieldIsNotPresent() {
        LabOrderRequestDTO dto = objectMapper.readValue("{\"testName\":\"CBC\"}", LabOrderRequestDTO.class);

        assertThat(dto.hasPerformingHospitalId()).isFalse();
        assertThat(dto.getPerformingHospitalId()).isNull();
    }

    @Test
    void explicitNullIsPresent() {
        LabOrderRequestDTO dto = objectMapper.readValue(
            "{\"testName\":\"CBC\",\"performingHospitalId\":null}", LabOrderRequestDTO.class);

        assertThat(dto.hasPerformingHospitalId()).isTrue();
        assertThat(dto.getPerformingHospitalId()).isNull();
    }

    @Test
    void anIdIsPresentAndCarried() {
        UUID labId = UUID.randomUUID();
        LabOrderRequestDTO dto = objectMapper.readValue(
            "{\"performingHospitalId\":\"" + labId + "\"}", LabOrderRequestDTO.class);

        assertThat(dto.hasPerformingHospitalId()).isTrue();
        assertThat(dto.getPerformingHospitalId()).isEqualTo(labId);
    }

    @Test
    void theBuilderMarksPresenceExactlyAsTheSetterDoes() {
        assertThat(LabOrderRequestDTO.builder().build().hasPerformingHospitalId()).isFalse();
        assertThat(LabOrderRequestDTO.builder().performingHospitalId(null).build()
            .hasPerformingHospitalId()).isTrue();

        LabOrderRequestDTO viaSetter = new LabOrderRequestDTO();
        assertThat(viaSetter.hasPerformingHospitalId()).isFalse();
        viaSetter.setPerformingHospitalId(null);
        assertThat(viaSetter.hasPerformingHospitalId()).isTrue();
    }

    @Test
    void thePresenceFlagIsNotSerialised() {
        String json = objectMapper.writeValueAsString(
            LabOrderRequestDTO.builder().performingHospitalId(null).build());

        assertThat(json).doesNotContain("performingHospitalIdPresent");
    }
}
