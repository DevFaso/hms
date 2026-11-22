package com.example.hms.mapper;

import com.example.hms.model.Patient;
import com.example.hms.model.TransfusionRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA r70 D3221: the request carries the blood group recorded on the chart, beside the lab-typed one (which it
 * never replaces), so the blood bank can show it as declared and flag a disagreement.
 */
class TransfusionMapperDeclaredGroupTest {

    private final TransfusionMapper mapper = new TransfusionMapper(Clock.systemDefaultZone());

    @Test
    void untypedRequestStillCarriesTheChartGroup() {
        Patient patient = Patient.builder().firstName("Awa").lastName("Test").bloodType("O+").build();
        patient.setId(UUID.randomUUID());
        TransfusionRequest request = new TransfusionRequest();
        request.setId(UUID.randomUUID());
        request.setPatient(patient);

        var dto = mapper.toDto(request, List.of(), List.of());

        assertThat(dto.getPatientDeclaredBloodType()).isEqualTo("O+");
        // Declared is not typed: no lab group appears from the chart's value.
        assertThat(dto.getPatientAboGroup()).isNull();
        assertThat(dto.getBloodGroupId()).isNull();
    }

    @Test
    void noChartGroupStaysEmpty() {
        Patient patient = Patient.builder().firstName("Awa").lastName("Test").build();
        TransfusionRequest request = new TransfusionRequest();
        request.setPatient(patient);

        assertThat(mapper.toDto(request, List.of(), List.of()).getPatientDeclaredBloodType()).isNull();
    }
}
