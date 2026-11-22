package com.example.hms.service.billing;

import com.example.hms.model.PatientInsurance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * QA D3250 (r70): the ONE order in which a patient's coverages at a hospital are tried, shared by every money path:
 * the invoice's payer share ({@link InvoiceCoverageService}), the desk's « Assurance vérifiée » at check-in, the
 * front-desk snapshot and its alerts, and the coverage shown in the check-in dialog. A coverage is in force on a day
 * when its own dates cover it; in-force coverages are tried primary first, then the oldest.
 *
 * <p>Before r70 the desk read only the coverage flagged primary while the invoice took any coverage in force, so a
 * patient's only policy, linked without « principale », was billed to the payer while the desk said « Problème
 * d'assurance » and the check-in tick recorded nothing. {@code CoverageOrderGuardTest} keeps every reader on this
 * class.
 */
public final class CoverageOrder {

    /** Primary first, then the oldest, then by id (a stable order for rows written in the same instant). */
    private static final Comparator<PatientInsurance> ORDER =
        Comparator.comparing((PatientInsurance insurance) -> !insurance.isPrimary())
            .thenComparing(PatientInsurance::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(insurance -> String.valueOf(insurance.getId()));

    private CoverageOrder() {
    }

    /** Whether the coverage's own dates cover {@code day} (an open date never excludes it). */
    public static boolean inForceOn(PatientInsurance insurance, LocalDate day) {
        if (insurance == null || day == null) {
            return false;
        }
        return (insurance.getEffectiveDate() == null || !insurance.getEffectiveDate().isAfter(day))
            && (insurance.getExpirationDate() == null || !insurance.getExpirationDate().isBefore(day));
    }

    /** The coverages in force on {@code day}, in the order they are tried: primary first, then the oldest. */
    public static List<PatientInsurance> ordered(Collection<PatientInsurance> coverages, LocalDate day) {
        if (coverages == null) {
            return List.of();
        }
        return coverages.stream()
            .filter(insurance -> inForceOn(insurance, day))
            .sorted(ORDER)
            .toList();
    }

    /** Whether one of the coverages is flagged primary. */
    public static boolean hasPrimary(Collection<PatientInsurance> coverages) {
        return coverages != null && coverages.stream().anyMatch(PatientInsurance::isPrimary);
    }

    /** Whatever their dates (an expired coverage shown as such): primary first, then the oldest. */
    public static Optional<PatientInsurance> preferred(Collection<PatientInsurance> coverages) {
        if (coverages == null) {
            return Optional.empty();
        }
        return coverages.stream().sorted(ORDER).findFirst();
    }

    /** The first coverage tried on {@code day}, if any is in force. */
    public static Optional<PatientInsurance> first(Collection<PatientInsurance> coverages, LocalDate day) {
        return ordered(coverages, day).stream().findFirst();
    }

    /**
     * Whether the desk cannot tell which coverage applies: several are in force and none is flagged primary. One
     * coverage in force is the one that applies, flagged or not.
     */
    public static boolean ambiguous(Collection<PatientInsurance> coverages, LocalDate day) {
        List<PatientInsurance> inForce = ordered(coverages, day);
        return inForce.size() > 1 && inForce.stream().noneMatch(PatientInsurance::isPrimary);
    }
}
