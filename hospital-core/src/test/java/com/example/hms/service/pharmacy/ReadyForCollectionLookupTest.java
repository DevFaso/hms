package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.pharmacy.DispenseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G15 AC-12: readiness for the medication reads. */
@ExtendWith(MockitoExtension.class)
class ReadyForCollectionLookupTest {

    @Mock private DispenseRepository dispenseRepository;
    @InjectMocks private ReadyForCollectionLookup lookup;

    private static Prescription rx(PrescriptionStatus status) {
        Prescription p = new Prescription();
        p.setId(UUID.randomUUID());
        p.setStatus(status);
        return p;
    }

    private static Dispense pendingFor(Prescription rx, LocalDateTime readyAt) {
        Pharmacy pharmacy = Pharmacy.builder().name("Pharmacie Centrale").build();
        Dispense d = Dispense.builder()
                .prescription(rx)
                .pharmacy(pharmacy)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .status(DispenseStatus.PENDING)
                .build();
        d.setCreatedAt(readyAt);
        return d;
    }

    @Test
    @DisplayName("an open preparation is reported with its time and pharmacy, keyed by prescription; one query")
    void reportsTheOpenPreparation() {
        Prescription waiting = rx(PrescriptionStatus.SIGNED);
        Prescription other = rx(PrescriptionStatus.PARTIALLY_FILLED);
        LocalDateTime readyAt = LocalDateTime.of(2026, 10, 6, 15, 30);
        when(dispenseRepository.findByPrescription_IdInAndStatus(List.of(waiting.getId(), other.getId()),
                DispenseStatus.PENDING)).thenReturn(List.of(pendingFor(waiting, readyAt)));

        Map<UUID, ReadyForCollectionLookup.Readiness> ready = lookup.openPreparations(List.of(waiting, other));

        assertThat(ready).containsOnlyKeys(waiting.getId());
        assertThat(ready.get(waiting.getId()).readyAt()).isEqualTo(readyAt);
        assertThat(ready.get(waiting.getId()).pharmacyName()).isEqualTo("Pharmacie Centrale");
    }

    @Test
    @DisplayName("a withdrawn prescription never reports readiness, even if a stale row says so")
    void withdrawnNeverReports() {
        Prescription cancelled = rx(PrescriptionStatus.CANCELLED);
        Prescription discontinued = rx(PrescriptionStatus.DISCONTINUED);

        assertThat(lookup.openPreparations(List.of(cancelled, discontinued))).isEmpty();
        verify(dispenseRepository, never()).findByPrescription_IdInAndStatus(any(), any());
    }

    @Test
    @DisplayName("in a mixed list only the live orders are asked about")
    void asksOnlyAboutLiveOrders() {
        Prescription live = rx(PrescriptionStatus.SIGNED);
        Prescription cancelled = rx(PrescriptionStatus.CANCELLED);

        lookup.openPreparations(List.of(live, cancelled));

        verify(dispenseRepository).findByPrescription_IdInAndStatus(List.of(live.getId()), DispenseStatus.PENDING);
    }
}
