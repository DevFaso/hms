package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.pharmacy.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PharmacyConsumptionServiceTest {
    @Mock StockCountService access;
    @Mock PharmacyRepository pharmacies;
    @Mock StockTransactionRepository movements;
    @Mock MedicationCatalogItemRepository catalogue;
    private PharmacyConsumptionService service;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();

    @BeforeEach
    void fixture() {
        service = new PharmacyConsumptionService(access, pharmacies, movements, catalogue,
            Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneOffset.UTC));
        when(access.readerHospital()).thenReturn(hospitalId);
    }

    @Test
    void differentRecordedUnitsStaySeparateAndLegacyUnknownUnitsAreNotInventedOrShownAsZero() {
        when(pharmacies.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(new Pharmacy()));
        UUID item = UUID.randomUUID();
        var medicine = MedicationCatalogItem.builder().nameFr("Synthetic medicine").genericName("Synthetic medicine").build();
        medicine.setId(UUID.randomUUID());
        Object[] tablets = {item, medicine.getId(), "tablet", new BigDecimal("20"), new BigDecimal("2"), 4L};
        Object[] boxes = {item, medicine.getId(), "box", new BigDecimal("3"), BigDecimal.ZERO, 1L};
        Object[] oldUnknown = {item, medicine.getId(), null, new BigDecimal("150"), BigDecimal.ZERO, 7L};
        when(movements.consumptionByRecordedUnit(any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(tablets, boxes, oldUnknown));
        when(catalogue.findAllById(Set.of(medicine.getId()))).thenReturn(List.of(medicine));
        var report = service.report(pharmacyId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 25));
        assertThat(report.items()).hasSize(3);
        var unknown = report.items().stream().filter(row -> !row.unitHistoryComparable()).findFirst().orElseThrow();
        assertThat(unknown.issuedQuantity()).isNull();
        assertThat(unknown.returnedQuantity()).isNull();
        assertThat(unknown.sourceMovements()).isEqualTo(7);
        assertThat(report.items().stream().filter(row -> "tablet".equals(row.unit())).findFirst().orElseThrow().issuedQuantity())
            .isEqualByComparingTo("20");
        verify(movements).consumptionByRecordedUnit(pharmacyId, LocalDateTime.of(2026, 11, 1, 0, 0),
            LocalDateTime.of(2026, 11, 26, 0, 0), Set.of(StockTransactionType.DISPENSE, StockTransactionType.SALE),
            StockTransactionType.RETURN, StockTransactionType.TRANSFER);
        verify(catalogue, times(1)).findAllById(any());
    }

    @Test
    void anotherHospitalsPharmacyIsNotFoundBeforeAnyMovementQuery() {
        when(pharmacies.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.report(pharmacyId, null, null)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(movements, catalogue);
    }

    @Test
    void anInvertedOrUnboundedPeriodIsRefusedBeforeReadingTheLedger() {
        when(pharmacies.findByIdAndHospital_Id(pharmacyId, hospitalId)).thenReturn(Optional.of(new Pharmacy()));
        assertThatThrownBy(() -> service.report(pharmacyId, LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 25)))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.report(pharmacyId, LocalDate.of(2024, 1, 1), LocalDate.of(2026, 11, 25)))
            .isInstanceOf(FieldBusinessException.class);
        verifyNoInteractions(movements, catalogue);
    }
}
