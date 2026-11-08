package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceNumberSequence;
import com.example.hms.repository.InvoiceNumberSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Hands out invoice numbers per hospital and year (QA D94): {@code CODE-YYYY-000001}.
 *
 * <p>The counter row is read under a row lock in its own transaction, so the
 * lock is held only for the increment, not for the whole invoice create. The
 * price is that a create which fails after allocating leaves a gap in the
 * sequence; numbers are never reused, which is what an auditor checks.
 *
 * <p>The very first allocation of a year inserts the counter row. When two
 * requests race on that insert the loser's transaction fails on the unique
 * key ({@code DataIntegrityViolationException}); the caller retries once and
 * then finds the winner's row.
 */
@Service
@RequiredArgsConstructor
public class InvoiceNumberAllocator {

    private static final int MAX_PREFIX = 20;

    private final InvoiceNumberSequenceRepository sequenceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(Hospital hospital, LocalDate today) {
        int year = today.getYear();
        InvoiceNumberSequence sequence = sequenceRepository.findForUpdate(hospital.getId(), year)
            .orElseGet(() -> {
                InvoiceNumberSequence created = new InvoiceNumberSequence();
                created.setHospitalId(hospital.getId());
                created.setSequenceYear(year);
                created.setLastValue(0L);
                return created;
            });
        long value = sequence.getLastValue() + 1;
        sequence.setLastValue(value);
        sequenceRepository.saveAndFlush(sequence);
        return format(prefixOf(hospital), year, value);
    }

    static String format(String prefix, int year, long value) {
        return String.format(Locale.ROOT, "%s-%d-%06d", prefix, year, value);
    }

    /** The hospital code reduced to letters and digits, or INV when it has none. */
    public static String prefixOf(Hospital hospital) {
        String code = hospital.getCode() == null ? "" : hospital.getCode();
        String cleaned = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (cleaned.isEmpty()) {
            return "INV";
        }
        return cleaned.length() > MAX_PREFIX ? cleaned.substring(0, MAX_PREFIX) : cleaned;
    }
}
