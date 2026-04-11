package com.example.hms.mapper;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.Encounter;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InBasketMapperTest {

    private InBasketMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new InBasketMapper();
    }

    @Test
    void toDto_nullReturnsNull() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toDto_mapsAllFields() {
        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        Patient patient = new Patient();
        patient.setId(patientId);

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);

        InBasketItem item = InBasketItem.builder()
                .itemType(InBasketItemType.RESULT)
                .priority(InBasketPriority.CRITICAL)
                .status(com.example.hms.enums.InBasketItemStatus.UNREAD)
                .title("CRITICAL: Troponin I > 2.0")
                .body("Lab result requires immediate attention")
                .referenceId(referenceId)
                .referenceType("LAB_RESULT")
                .patientName("John Doe")
                .orderingProviderName("Dr. Smith")
                .build();
        item.setId(itemId);
        item.setCreatedAt(now);
        item.setEncounter(encounter);
        item.setPatient(patient);
        item.setReadAt(now.plusMinutes(5));
        item.setAcknowledgedAt(now.plusMinutes(10));

        InBasketItemDTO dto = mapper.toDto(item);

        assertThat(dto.getId()).isEqualTo(itemId);
        assertThat(dto.getItemType()).isEqualTo("RESULT");
        assertThat(dto.getPriority()).isEqualTo("CRITICAL");
        assertThat(dto.getStatus()).isEqualTo("UNREAD");
        assertThat(dto.getTitle()).isEqualTo("CRITICAL: Troponin I > 2.0");
        assertThat(dto.getBody()).isEqualTo("Lab result requires immediate attention");
        assertThat(dto.getReferenceId()).isEqualTo(referenceId);
        assertThat(dto.getReferenceType()).isEqualTo("LAB_RESULT");
        assertThat(dto.getEncounterId()).isEqualTo(encounterId);
        assertThat(dto.getPatientId()).isEqualTo(patientId);
        assertThat(dto.getPatientName()).isEqualTo("John Doe");
        assertThat(dto.getOrderingProviderName()).isEqualTo("Dr. Smith");
        assertThat(dto.getCreatedAt()).isEqualTo(now);
        assertThat(dto.getReadAt()).isEqualTo(now.plusMinutes(5));
        assertThat(dto.getAcknowledgedAt()).isEqualTo(now.plusMinutes(10));
    }

    @Test
    void toDto_nullEncounterAndPatient_setsNullIds() {
        InBasketItem item = InBasketItem.builder()
                .itemType(InBasketItemType.ORDER)
                .priority(InBasketPriority.NORMAL)
                .status(com.example.hms.enums.InBasketItemStatus.READ)
                .title("New imaging order")
                .build();

        InBasketItemDTO dto = mapper.toDto(item);

        assertThat(dto.getEncounterId()).isNull();
        assertThat(dto.getPatientId()).isNull();
    }

    @Test
    void toDto_nullEnums_producesNullStrings() {
        InBasketItem item = InBasketItem.builder()
                .title("Test item")
                .build();
        // Override builder defaults to null
        item.setItemType(null);
        item.setPriority(null);
        item.setStatus(null);

        InBasketItemDTO dto = mapper.toDto(item);

        assertThat(dto.getItemType()).isNull();
        assertThat(dto.getPriority()).isNull();
        assertThat(dto.getStatus()).isNull();
    }
}
