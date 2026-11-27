package com.example.hms.service.impl;

import com.example.hms.enums.MedicationCatalogVersionKind;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MedicationCatalogItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.DoseLimitSnapshot;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.PediatricWeightBand;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.payload.dto.medication.PediatricDosingRequestDTO;
import com.example.hms.payload.dto.medication.PediatricWeightBandDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.formulary.MedicationCatalogVersionService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 18 (QA D935): the pharmacy sets an item's paediatric dose limits through
 * {@code updatePediatricDosing}: scoped to the caller's acting hospital, and limits that contradict each other
 * refused.
 *
 * <p>Go-live D2 (V385): the limits sent are PROPOSED as a version awaiting approval. The catalogue row, which
 * is all the dose check reads, keeps the limits in force until another person approves the version
 * ({@code MedicationCatalogVersionServiceTest} holds the approval itself).
 */
class MedicationCatalogPediatricDosingTest {

    private final MedicationCatalogItemRepository repository = mock(MedicationCatalogItemRepository.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final MedicationCatalogVersionService versionService = mock(MedicationCatalogVersionService.class);
    private final MedicationCatalogItemServiceImpl catalogService = new MedicationCatalogItemServiceImpl(
        repository, mock(HospitalRepository.class), new MedicationCatalogItemMapper(), audit, roleValidator,
        versionService);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID pharmacistId = UUID.randomUUID();
    private MedicationCatalogItem item;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        ReflectionTestUtils.setField(hospital, "id", hospitalId);
        item = MedicationCatalogItem.builder().nameFr("Paracétamol").genericName("Paracetamol").hospital(hospital)
            .pediatricMaxDoseMgPerKg(new BigDecimal("10")).pediatricDoseStatus(PediatricDoseLimitStatus.APPROVED)
            .build();
        ReflectionTestUtils.setField(item, "id", itemId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(pharmacistId);
        when(repository.findByIdAndHospital_Id(itemId, hospitalId)).thenReturn(Optional.of(item));
    }

    private static PediatricDosingRequestDTO paracetamol() {
        return PediatricDosingRequestDTO.builder()
            .pediatricMaxDoseMgPerKg(new BigDecimal("15"))
            .pediatricMaxDailyMgPerKg(new BigDecimal("60"))
            .pediatricMaxSingleDoseMg(new BigDecimal("1000"))
            .pediatricMaxDailyMg(new BigDecimal("4000"))
            .pediatricDoseSource("  WHO Pocket Book 2013; BNFc  ")
            .changeReason("Limites OMS")
            .build();
    }

    private DoseLimitSnapshot proposed(String reason) {
        ArgumentCaptor<DoseLimitSnapshot> limits = ArgumentCaptor.forClass(DoseLimitSnapshot.class);
        verify(versionService).proposeDoseLimits(same(item), limits.capture(), eq(reason));
        return limits.getValue();
    }

    @Test
    void everyLimitIsProposed_andTheRowKeepsTheLimitsInForce() {
        UUID pendingVersion = UUID.randomUUID();
        when(versionService.pendingByEntry(List.of(itemId)))
            .thenReturn(Map.of(itemId, Map.of(MedicationCatalogVersionKind.DOSE_LIMIT, pendingVersion)));

        MedicationCatalogItemResponseDTO answer = catalogService.updatePediatricDosing(itemId, paracetamol());

        DoseLimitSnapshot limits = proposed("Limites OMS");
        assertThat(limits.maxDoseMgPerKg()).isEqualByComparingTo("15");
        assertThat(limits.maxDailyMgPerKg()).isEqualByComparingTo("60");
        assertThat(limits.maxSingleDoseMg()).isEqualByComparingTo("1000");
        assertThat(limits.maxDailyMg()).isEqualByComparingTo("4000");
        assertThat(limits.source()).isEqualTo("WHO Pocket Book 2013; BNFc");
        // What the dose check reads has not moved, and the answer says a version is waiting.
        assertThat(item.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("10");
        assertThat(item.getPediatricMaxDailyMg()).isNull();
        assertThat(answer.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("10");
        assertThat(answer.getPendingDoseLimitVersionId()).isEqualTo(pendingVersion);
        assertThat(answer.getDoseLimitApprovedVersion()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void theCallerNoLongerDeclaresTheLimitsApproved() {
        PediatricDosingRequestDTO selfApproved = paracetamol();
        selfApproved.setPediatricDoseStatus(PediatricDoseLimitStatus.APPROVED);
        item = MedicationCatalogItem.builder().nameFr("Ibuprofène").genericName("Ibuprofen")
            .hospital(item.getHospital()).build();
        ReflectionTestUtils.setField(item, "id", itemId);
        when(repository.findByIdAndHospital_Id(itemId, hospitalId)).thenReturn(Optional.of(item));

        MedicationCatalogItemResponseDTO answer = catalogService.updatePediatricDosing(itemId, selfApproved);

        assertThat(item.getPediatricDoseStatus()).isNull();
        assertThat(item.getPediatricDoseReviewedBy()).isNull();
        assertThat(answer.getPediatricDoseStatus()).isNull();
        proposed("Limites OMS");
    }

    @Test
    void weightBandsAreProposedInWeightOrder() {
        PediatricDosingRequestDTO bands = PediatricDosingRequestDTO.builder()
            .pediatricWeightBands(List.of(
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("15")).maxWeightKg(new BigDecimal("25"))
                    .dosePerAdministration(new BigDecimal("2")).doseUnit("cp").maxDosesPerDay(2).build(),
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("5")).maxWeightKg(new BigDecimal("15"))
                    .dosePerAdministration(BigDecimal.ONE).doseUnit("cp").maxDosesPerDay(2).build()))
            .changeReason("Tranches OMS")
            .build();

        catalogService.updatePediatricDosing(itemId, bands);

        assertThat(proposed("Tranches OMS").weightBands()).extracting(PediatricWeightBand::getMinWeightKg)
            .containsExactly(new BigDecimal("5"), new BigDecimal("15"));
        assertThat(item.getPediatricWeightBands()).isEmpty();
    }

    @Test
    void overlappingBandsAreRefused() {
        PediatricDosingRequestDTO overlap = PediatricDosingRequestDTO.builder()
            .pediatricWeightBands(List.of(
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("5")).maxWeightKg(new BigDecimal("20"))
                    .dosePerAdministration(BigDecimal.ONE).doseUnit("cp").maxDosesPerDay(2).build(),
                PediatricWeightBandDTO.builder().minWeightKg(new BigDecimal("15")).maxWeightKg(new BigDecimal("25"))
                    .dosePerAdministration(new BigDecimal("2")).doseUnit("cp").maxDosesPerDay(2).build()))
            .changeReason("Tranches")
            .build();

        assertThatThrownBy(() -> catalogService.updatePediatricDosing(itemId, overlap))
            .isInstanceOf(FieldBusinessException.class);
        verify(versionService, never()).proposeDoseLimits(any(), any(), any());
    }

    @Test
    void aPerDoseMaximumAboveThePerDayMaximumIsRefused() {
        PediatricDosingRequestDTO inverted = paracetamol();
        inverted.setPediatricMaxDoseMgPerKg(new BigDecimal("90"));

        assertThatThrownBy(() -> catalogService.updatePediatricDosing(itemId, inverted))
            .isInstanceOf(FieldBusinessException.class);
        verify(versionService, never()).proposeDoseLimits(any(), any(), any());
    }

    @Test
    void anotherHospitalsItemIsNotFound() {
        UUID otherItem = UUID.randomUUID();
        when(repository.findByIdAndHospital_Id(otherItem, hospitalId)).thenReturn(Optional.empty());

        var dosing = paracetamol();
        assertThatThrownBy(() -> catalogService.updatePediatricDosing(otherItem, dosing))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(versionService, never()).proposeDoseLimits(any(), any(), any());
    }

    @Test
    void withoutAnActingHospitalNothingIsFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        var dosing = paracetamol();
        assertThatThrownBy(() -> catalogService.updatePediatricDosing(itemId, dosing))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(versionService, never()).proposeDoseLimits(any(), any(), any());
    }
}
