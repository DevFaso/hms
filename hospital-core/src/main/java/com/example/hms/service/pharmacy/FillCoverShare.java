package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.service.billing.InvoiceCoverageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3406 / D3408 (r70): what a pharmacy fill costs, and who pays which part of it, read the same way by the
 * deferral cards, their approval, the checkout and the claim.
 *
 * <ul>
 *   <li>The <b>prepared fill</b> of a prescription (its PENDING dispense, at most one) is what a deferral is asked
 *       for: its charge, what was paid on it, and what is still due. The approver sees it, and an outpatient credit
 *       below it is refused at approval (D3406), not at the hand-over.</li>
 *   <li>The <b>insurer share</b> of a fill comes from the patient's coverage by the invoice's rule
 *       ({@link InvoiceCoverageService#quote}), never from a typed figure (D3408). A third-party cover records it at
 *       approval as the most that may leave on it; the patient's share is paid at the checkout (or the fill stays
 *       at the counter), and the claim is that share.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class FillCoverShare {

    /** A third-party cover that still stands: approved, or a fill left on it. */
    static final Set<DispenseBillingDeferralStatus> COVER_STANDING = EnumSet.of(DispenseBillingDeferralStatus.APPROVED,
            DispenseBillingDeferralStatus.EXECUTED);

    private final DispenseRepository dispenseRepository;
    private final DispenseBillingDeferralRepository deferralRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final PharmacyPaymentRepository paymentRepository;
    private final PharmacyClaimRepository claimRepository;
    private final InvoiceCoverageService invoiceCoverage;
    private final Clock clock;

    /** The prepared fill of a prescription: its charge, what was paid on it, and what is still due. */
    public record PreparedFill(UUID dispenseId, BigDecimal charge, BigDecimal paid, BigDecimal due) {
    }

    /** The prepared (PENDING) fill of {@code prescriptionId} at {@code hospitalId}, priced; empty when none. */
    public Optional<PreparedFill> preparedFill(UUID prescriptionId, UUID hospitalId) {
        if (prescriptionId == null || hospitalId == null) {
            return Optional.empty();
        }
        return dispenseRepository.findFirstByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING)
                .filter(fill -> atHospital(fill, hospitalId))
                .flatMap(fill -> DispenseCharge.of(fill, inventoryItemRepository).map(charge -> {
                    BigDecimal paid = paidOn(fill);
                    return new PreparedFill(fill.getId(), charge, paid, charge.subtract(paid).max(BigDecimal.ZERO));
                }));
    }

    /**
     * The insurer share of {@code charge} for the patient at {@code hospitalId} today, by the coverage rule an invoice
     * uses (no pharmacy category: the plan's default rate, its ceilings); empty when no coverage with a plan applies.
     */
    public Optional<BigDecimal> planShare(UUID patientId, UUID hospitalId, BigDecimal charge, UUID prescriptionId) {
        if (patientId == null || hospitalId == null || charge == null) {
            return Optional.empty();
        }
        return invoiceCoverage.quoteForPrescription(patientId, hospitalId, LocalDate.now(clock), charge,
                        prescriptionId)
                .map(InvoiceCoverageService.CoverageQuote::payerShare);
    }

    /**
     * Review of #1284: the share a cover recorded is of the fill prepared when it was approved; the fill that leaves
     * may be another one (cancelled, prepared again, smaller). What the insurer pays of THIS fill is the recorded share
     * or the plan's share of this fill's charge, whichever is less; nothing when the plan no longer applies.
     */
    public BigDecimal capOf(BigDecimal recorded, UUID patientId, UUID hospitalId, BigDecimal charge,
                            UUID prescriptionId) {
        if (recorded == null) {
            return BigDecimal.ZERO;
        }
        if (charge == null) {
            return recorded;
        }
        return recorded.min(planShare(patientId, hospitalId, charge, prescriptionId).orElse(BigDecimal.ZERO));
    }

    /**
     * The insurer share recorded on the third-party cover this fill left on, or that stands approved for its
     * prescription; empty when the fill has none.
     */
    public Optional<BigDecimal> coverShare(Dispense fill, UUID hospitalId) {
        if (fill == null || hospitalId == null) {
            return Optional.empty();
        }
        UUID patientId = fill.getPatient() != null ? fill.getPatient().getId() : null;
        UUID prescriptionId = fill.getPrescription() != null ? fill.getPrescription().getId() : null;
        BigDecimal charge = DispenseCharge.of(fill, inventoryItemRepository).orElse(null);
        return recordedCoverShare(fill, hospitalId)
                .map(recorded -> capOf(recorded, patientId, hospitalId, charge, prescriptionId));
    }

    /** The share the cover recorded at approval, before it is measured against this fill. */
    private Optional<BigDecimal> recordedCoverShare(Dispense fill, UUID hospitalId) {
        if (fill.getBillingDeferralId() != null) {
            return deferralRepository.findByHospitalIdAndIdIn(hospitalId, java.util.List.of(fill.getBillingDeferralId()))
                    .stream()
                    .filter(FillCoverShare::isCover)
                    .map(DispenseBillingDeferral::getCreditLimit)
                    .filter(Objects::nonNull)
                    .findFirst();
        }
        UUID prescriptionId = fill.getPrescription() != null ? fill.getPrescription().getId() : null;
        if (prescriptionId == null) {
            return Optional.empty();
        }
        return deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(hospitalId,
                        prescriptionId, EnumSet.of(DispenseBillingDeferralStatus.APPROVED)).stream()
                .filter(FillCoverShare::isCover)
                .map(DispenseBillingDeferral::getCreditLimit)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * What the insurer pays of this fill: the share a live claim asks for, else the share its third-party cover
     * recorded; zero when neither. The patient owes the charge less this. Every read here runs in the caller's
     * transaction (the counter's, the checkout's, the claim's, the card's).
     */
    public BigDecimal insurerShare(Dispense fill, UUID hospitalId) {
        BigDecimal claimed = fill != null && fill.getId() != null
                ? Objects.requireNonNullElse(claimRepository.sumLiveForDispense(fill.getId()), BigDecimal.ZERO)
                : BigDecimal.ZERO;
        BigDecimal cover = coverShare(fill, hospitalId).orElse(BigDecimal.ZERO);
        return claimed.max(cover);
    }

    private static boolean isCover(DispenseBillingDeferral deferral) {
        return deferral.getKind() == DispenseBillingDeferralKind.THIRD_PARTY_COVER
                && COVER_STANDING.contains(deferral.getStatus());
    }

    private BigDecimal paidOn(Dispense fill) {
        return Objects.requireNonNullElse(paymentRepository.sumPostedForDispense(fill.getId()), BigDecimal.ZERO);
    }

    private static boolean atHospital(Dispense fill, UUID hospitalId) {
        return fill.getPharmacy() != null && fill.getPharmacy().getHospital() != null
                && hospitalId.equals(fill.getPharmacy().getHospital().getId());
    }
}
