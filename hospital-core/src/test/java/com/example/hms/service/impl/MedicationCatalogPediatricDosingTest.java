package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MedicationCatalogItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.payload.dto.medication.PediatricDosingRequestDTO;
import com.example.hms.payload.dto.medication.PediatricWeightBandDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 18 (QA D935): the pharmacy sets and approves an item's paediatric
 * dose limits through {@code updatePediatricDosing}: scoped to the caller's
 * acting hospital, every limit written and read back, audited, and limits
 * that contradict each other refused.
 */
class MedicationCatalogPediatricDosingTest {

    private final MedicationCatalogItemRepository repository = mock(MedicationCatalogItemRepository.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final MedicationCatalogItemServiceImpl catalogService = new MedicationCatalogItemServiceImpl(
        repository, mock(HospitalRepository.class), new MedicationCatalogItemMapper(), audit, roleValidator);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID pharmacistId = UUID.randomUUID();
    private MedicationCatalogItem item;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        ReflectionTestUtils.setField(hospital, "id", hospitalId);
        item = MedicationCatalogItem.builder().nameFr("Paracétamol").genericName("Paracetamol").hospital(hospital).build();
        ReflectionTestUtils.setField(item, "id", itemId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(pharmacistId);
        when(repository.findByIdAndHospital_Id(itemId, hospitalId)).thenReturn(Optional.of(item));
        when(repository.save(any(MedicationCatalogItem.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static PediatricDosingRequestDTO paracetamol() {
        return PediatricDosingRequestDTO.builder()
            .pediatricMaxDoseMgPerKg(new BigDecimal("15"))
            .pediatricMaxDailyMgPerKg(new BigDecimal("60"))
            .pediatricMaxSingleDoseMg(new BigDecimal("1000"))
            .pediatricMaxDailyMg(new BigDecimal("4000"))
            .pediatricDoseSource("  WHO Pocket Book 2013; BNFc  ")
            .pediatricDoseStatus(PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL)
            .build();
    }

    @Test
    void everyLimitIsWrittenAndReadBackWithTheReviewer() {
        MedicationCatalogItemResponseDTO saved = catalogService.updatePediatricDosing(itemId, paracetamol());

        assertThat(saved.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("15");
        assertThat(saved.getPediatricMaxDailyMgPerKg()).isEqualByComparingTo("60");
        assertThat(saved.getPediatricMaxSingleDoseMg()).isEqualByComparingTo("1000");
        assertThat(saved.getPediatricMaxDailyMg()).isEqualByComparingTo("4000");
        assertThat(saved.getPediatricDoseSource()).isEqualTo("WHO Pocket Book 2013; BNFc");
        assertThat(saved.getPediatricDoseStatus()).isEqualTo(PediatricDoseLimitStatus.DEFAULT_PENDING_APPROVAL);
        assertThat(saved.getPediatricDoseReviewedBy()).isEqualTo(pharmacistId);
        assertThat(saved.getPediatricDoseReviewedAt()).isNotNull();
        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo(AuditEventType.MEDICATION_DOSE_LIMITS_UPDATED);
    }

    @Test
    void weightBandsAreStoredInWeightOrder() {
        PediatricDosingRequestDTO bands = PediatricDosingRequestDTO.builder()
            .pediatricWeightBands(List.of(
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("15")).maxWeightKg(new BigDecimal("25"))
                    .dosePerAdministration(new BigDecimal("2")).doseUnit("cp").maxDosesPerDay(2).build(),
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("5")).maxWeightKg(new BigDecimal("15"))
                    .dosePerAdministration(BigDecimal.ONE).doseUnit("cp").maxDosesPerDay(2).build()))
            .pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .build();

        MedicationCatalogItemResponseDTO saved = catalogService.updatePediatricDosing(itemId, bands);

        assertThat(saved.getPediatricWeightBands()).extracting(PediatricWeightBandDTO::getMinWeightKg)
            .containsExactly(new BigDecimal("5"), new BigDecimal("15"));
        assertThat(item.getPediatricWeightBands()).hasSize(2);
    }

    @Test
    void overlappingBandsAreRefused() {
        PediatricDosingRequestDTO overlap = PediatricDosingRequestDTO.builder()
            .pediatricWeightBands(List.of(
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("5")).maxWeightKg(new BigDecimal("20"))
                    .dosePerAdministration(BigDecimal.ONE).doseUnit("cp").maxDosesPerDay(2).build(),
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("15")).maxWeightKg(new BigDecimal("25"))
                    .dosePerAdministration(new BigDecimal("2")).doseUnit("cp").maxDosesPerDay(2).build()))
            .pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .build();

        assertThatThrownBy(() -> catalogService.updatePediatricDosing(itemId, overlap))
            .isInstanceOf(FieldBusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aPerDoseMaximumAboveThePerDayMaximumIsRefused() {
        PediatricDosingRequestDTO inverted = paracetamol();
        inverted.setPediatricMaxDoseMgPerKg(new BigDecimal("90"));

        assertThatThrownBy(() -> catalogService.updatePediatricDosing(itemId, inverted))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    void anotherHospitalsItemIsNotFound() {
        UUID otherItem = UUID.randomUUID();
        when(repository.findByIdAndHospital_Id(otherItem, hospitalId)).thenReturn(Optional.empty());

        var dosing = paracetamol();
        assertThatThrownBy(() -> catalogService.updatePediatricDosing(otherItem, dosing))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void withoutAnActingHospitalNothingIsFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        var dosing = paracetamol();
        assertThatThrownBy(() -> catalogService.updatePediatricDosing(itemId, dosing))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }
}
