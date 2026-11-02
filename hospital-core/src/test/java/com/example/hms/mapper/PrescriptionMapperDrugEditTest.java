package com.example.hms.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * QA D1147: an order edited to another drug named the first drug everywhere
 * that reads the display name (the list, the pharmacist's view, the record
 * PDF), because the copy was written only while blank.
 */
class PrescriptionMapperDrugEditTest {

    private final PrescriptionMapper mapper = new PrescriptionMapper();

    private static PrescriptionRequestDTO request(String name, String code, UUID itemId) {
        PrescriptionRequestDTO dto = new PrescriptionRequestDTO();
        dto.setMedicationName(name);
        dto.setMedicationCode(code);
        dto.setMedicationCatalogItemId(itemId);
        return dto;
    }

    @Test
    void anEditToAnotherDrugRenamesTheOrderEverywhere() {
        Prescription rx = mapper.toEntity(request("Amoxicilline", "AMOX500", UUID.randomUUID()), null, null, null);
        UUID morphine = UUID.randomUUID();

        mapper.updateEntity(rx, request("Morphine", "MORPH5", morphine), null, null, null);

        assertThat(rx.getMedicationDisplayName()).isEqualTo("Morphine");
        assertThat(rx.getMedicationName()).isEqualTo("Morphine");
        assertThat(rx.getMedicationCode()).isEqualTo("MORPH5");
        assertThat(rx.getMedicationCatalogItemId()).isEqualTo(morphine);
        assertThat(mapper.toResponseDTO(rx).getMedicationDisplayName()).isEqualTo("Morphine");
    }

    @Test
    void anEditToAnUncodedDrugDropsTheOldDrugsCode() {
        Prescription rx = mapper.toEntity(request("Amoxicilline", "AMOX500", null), null, null, null);

        mapper.updateEntity(rx, request("Paracétamol", null, null), null, null, null);

        assertThat(rx.getMedicationDisplayName()).isEqualTo("Paracétamol");
        assertThat(rx.getMedicationCode()).isNull();
    }

    @Test
    void anEditOfTheSameDrugWithoutACodeKeepsItsCode() {
        Prescription rx = mapper.toEntity(request("Amoxicilline", "AMOX500", null), null, null, null);

        mapper.updateEntity(rx, request(" amoxicilline ", null, null), null, null, null);

        assertThat(rx.getMedicationCode()).isEqualTo("AMOX500");
        assertThat(rx.getMedicationDisplayName()).isEqualTo(" amoxicilline ");
    }
}
