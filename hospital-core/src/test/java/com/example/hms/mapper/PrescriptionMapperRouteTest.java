package com.example.hms.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import org.junit.jupiter.api.Test;

/** D109: the prescriber's route reaches the order, so the MAR route check has something to compare. */
class PrescriptionMapperRouteTest {

    private final PrescriptionMapper mapper = new PrescriptionMapper();

    private static PrescriptionRequestDTO request(String route) {
        PrescriptionRequestDTO dto = new PrescriptionRequestDTO();
        dto.setMedicationName("Ceftriaxone");
        dto.setRoute(route);
        return dto;
    }

    @Test
    void createKeepsTheRouteNormalised() {
        Prescription rx = mapper.toEntity(request(" iv "), null, null, null);
        assertThat(rx.getRoute()).isEqualTo("IV");
        assertThat(mapper.toResponseDTO(rx).getRoute()).isEqualTo("IV");
    }

    @Test
    void anEditThatOmitsTheRouteKeepsTheRecordedOne() {
        Prescription rx = mapper.toEntity(request("IM"), null, null, null);
        mapper.updateEntity(rx, request(null), null, null, null);
        assertThat(rx.getRoute()).isEqualTo("IM");
        mapper.updateEntity(rx, request("PO"), null, null, null);
        assertThat(rx.getRoute()).isEqualTo("PO");
    }
}
