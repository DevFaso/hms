package com.example.hms.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.pharmacy.ControlledSubstanceGuard;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D654: a morphine prescription written without the prescriber's controlled
 * tick reports controlled = true from the catalog, while the two-factor
 * signing block stays on the prescriber's own tick.
 */
class PrescriptionMapperCatalogControlledTest {

    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    private Prescription morphineWithoutTick(PrescriptionMapper mapper) {
        PrescriptionRequestDTO dto = new PrescriptionRequestDTO();
        dto.setMedicationName("Morphine");
        dto.setMedicationCode("MORPH-10");
        Prescription rx = mapper.toEntity(dto, null, null, null);
        Hospital h = new Hospital();
        h.setId(HOSPITAL_ID);
        rx.setHospital(h);
        return rx;
    }

    private static PrescriptionMapper mapperWithCatalog(boolean controlled) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setControlled(controlled);
        MedicationCatalogItemRepository repo = mock(MedicationCatalogItemRepository.class);
        when(repo.findByHospitalIdAndCode(HOSPITAL_ID, "MORPH-10")).thenReturn(Optional.of(item));
        PrescriptionMapper mapper = new PrescriptionMapper();
        mapper.setCatalogRepository(repo);
        return mapper;
    }

    @Test
    void aMorphinePrescriptionReportsTheCatalogsControlledFlag() {
        PrescriptionMapper mapper = mapperWithCatalog(true);
        Prescription rx = morphineWithoutTick(mapper);
        assertThat(mapper.toResponseDTO(rx).isControlledSubstance()).isTrue();
    }

    @Test
    void signingIsNotNewlyBlockedByTheCatalogFlag() {
        PrescriptionMapper mapper = mapperWithCatalog(true);
        Prescription rx = morphineWithoutTick(mapper);
        mapper.toResponseDTO(rx);
        assertThat(rx.isControlledSubstance()).isFalse();
        assertThatCode(() -> new ControlledSubstanceGuard()
            .requireSafeguardsFor(rx, PrescriptionStatus.SIGNED)).doesNotThrowAnyException();
    }

    @Test
    void aNonControlledCatalogItemStaysUncontrolled() {
        PrescriptionMapper mapper = mapperWithCatalog(false);
        assertThat(mapper.toResponseDTO(morphineWithoutTick(mapper)).isControlledSubstance()).isFalse();
    }

    /**
     * QA r3/d654.js: POST /prescriptions with medicationName + medicationCatalogItemId
     * of a controlled item whose code is NULL (created in the UI). The flag
     * comes from the item id, at create and on every later read.
     */
    @Test
    void aCatalogPickWithANullCodeReportsTheItemsControlledFlag() {
        UUID itemId = UUID.randomUUID();
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setControlled(true);
        MedicationCatalogItemRepository repo = mock(MedicationCatalogItemRepository.class);
        when(repo.findByIdAndHospital_Id(itemId, HOSPITAL_ID)).thenReturn(Optional.of(item));
        PrescriptionMapper mapper = new PrescriptionMapper();
        mapper.setCatalogRepository(repo);

        PrescriptionRequestDTO dto = new PrescriptionRequestDTO();
        dto.setMedicationName("QA R9 Morphine 12878");
        dto.setMedicationCatalogItemId(itemId);
        Prescription rx = mapper.toEntity(dto, null, null, null);
        Hospital h = new Hospital();
        h.setId(HOSPITAL_ID);
        rx.setHospital(h);

        assertThat(rx.getMedicationCode()).isNull();
        assertThat(rx.getMedicationCatalogItemId()).isEqualTo(itemId);
        assertThat(mapper.toResponseDTO(rx).isControlledSubstance()).isTrue();
    }

    @Test
    void aCatalogItemOfAnotherHospitalDoesNotMarkTheOrder() {
        UUID itemId = UUID.randomUUID();
        MedicationCatalogItemRepository repo = mock(MedicationCatalogItemRepository.class);
        when(repo.findByIdAndHospital_Id(itemId, HOSPITAL_ID)).thenReturn(Optional.empty());
        PrescriptionMapper mapper = new PrescriptionMapper();
        mapper.setCatalogRepository(repo);
        PrescriptionRequestDTO dto = new PrescriptionRequestDTO();
        dto.setMedicationName("X");
        dto.setMedicationCatalogItemId(itemId);
        Prescription rx = mapper.toEntity(dto, null, null, null);
        Hospital h = new Hospital();
        h.setId(HOSPITAL_ID);
        rx.setHospital(h);
        assertThat(mapper.toResponseDTO(rx).isControlledSubstance()).isFalse();
    }
}
