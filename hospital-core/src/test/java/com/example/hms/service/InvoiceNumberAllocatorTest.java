package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceNumberSequence;
import com.example.hms.repository.InvoiceNumberSequenceRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** QA D94: invoice numbers come from a per-hospital, per-year counter. */
class InvoiceNumberAllocatorTest {

    private final InvoiceNumberSequenceRepository repository = mock(InvoiceNumberSequenceRepository.class);
    private final InvoiceNumberAllocator allocator = new InvoiceNumberAllocator(repository);

    private static Hospital hospital(String code) {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setCode(code);
        return hospital;
    }

    @Test
    void firstNumberOfTheYearStartsTheCounter() {
        Hospital hospital = hospital("chu-ouaga");
        when(repository.findForUpdate(hospital.getId(), 2026)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(allocator.next(hospital, LocalDate.of(2026, 3, 1))).isEqualTo("CHUOUAGA-2026-000001");
    }

    @Test
    void nextNumberIncrementsTheLockedCounter() {
        Hospital hospital = hospital(null);
        InvoiceNumberSequence row = new InvoiceNumberSequence();
        row.setHospitalId(hospital.getId());
        row.setSequenceYear(2026);
        row.setLastValue(41L);
        when(repository.findForUpdate(hospital.getId(), 2026)).thenReturn(Optional.of(row));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(allocator.next(hospital, LocalDate.of(2026, 3, 1))).isEqualTo("INV-2026-000042");
        assertThat(row.getLastValue()).isEqualTo(42L);
    }
}
