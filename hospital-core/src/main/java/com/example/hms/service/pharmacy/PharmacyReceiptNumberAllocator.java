package com.example.hms.service.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.pharmacy.PharmacyReceiptSequence;
import com.example.hms.repository.pharmacy.PharmacyReceiptSequenceRepository;
import com.example.hms.service.InvoiceNumberAllocator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

/**
 * QA D1032: hands out pharmacy receipt numbers per hospital and year,
 * {@code CODE-PH-YYYY-000001}, stored on the payment when it is recorded, so
 * a reprint always shows the number first printed. The receipt used to print
 * a slice of the payment UUID ("Nº 38D0FCFE16").
 *
 * <p>The invoice counter's rules (QA D94): the counter row is read under a row
 * lock in its own transaction, so the lock is held only for the increment; a
 * payment that fails after allocating leaves a gap, and numbers are never
 * reused. The first allocation of a year inserts the row; the loser of that
 * race fails on the unique key and the caller retries once.
 */
@Service
@RequiredArgsConstructor
public class PharmacyReceiptNumberAllocator {

    private final PharmacyReceiptSequenceRepository sequenceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(Hospital hospital, LocalDate today) {
        int year = today.getYear();
        PharmacyReceiptSequence sequence = sequenceRepository.findForUpdate(hospital.getId(), year)
            .orElseGet(() -> {
                PharmacyReceiptSequence created = new PharmacyReceiptSequence();
                created.setHospitalId(hospital.getId());
                created.setSequenceYear(year);
                created.setLastValue(0L);
                return created;
            });
        long value = sequence.getLastValue() + 1;
        sequence.setLastValue(value);
        sequenceRepository.saveAndFlush(sequence);
        return format(InvoiceNumberAllocator.prefixOf(hospital), year, value);
    }

    static String format(String prefix, int year, long value) {
        return String.format(Locale.ROOT, "%s-PH-%d-%06d", prefix, year, value);
    }
}
