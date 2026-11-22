package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

/**
 * Review of #1284 (QA D3408): what a patient's pharmacy insurer shares used of the plan's annual ceiling in a coverage
 * year, at one hospital. A pharmacy claim (any but a refused one) is a share the payer is asked for; a third-party
 * cover approved, or left on with no live claim yet, is one it will be asked for. Each fill counts once: a cover whose
 * fill already carries a live claim is counted by that claim. The fills of {@code excludedPrescriptionId} are left out,
 * so the share of a fill is never computed against itself.
 */
@Component
@RequiredArgsConstructor
public class PharmacyCoverUsage {

    private final PharmacyClaimRepository claimRepository;
    private final DispenseBillingDeferralRepository deferralRepository;
    private final com.example.hms.repository.pharmacy.DispenseRepository dispenseRepository;

    /** The pharmacy insurer shares of {@code patientId} at {@code hospitalId} in {@code year}; never null. */
    public BigDecimal usedInYear(UUID patientId, UUID hospitalId, int year, UUID excludedPrescriptionId) {
        if (patientId == null || hospitalId == null) {
            return BigDecimal.ZERO;
        }
        UUID excluded = excludedPrescriptionId != null ? excludedPrescriptionId : new UUID(0L, 0L);
        LocalDateTime from = LocalDateTime.of(year, java.time.Month.JANUARY, 1, 0, 0);
        LocalDateTime to = from.plusYears(1);
        BigDecimal used = Objects.requireNonNullElse(
                claimRepository.sumLiveForPatientBetween(patientId, hospitalId, from, to, excluded), BigDecimal.ZERO);
        for (DispenseBillingDeferral cover : deferralRepository
                .findByHospitalIdAndPatientIdAndKindAndStatusInAndRequestedAtBetween(hospitalId, patientId,
                        DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                        EnumSet.of(DispenseBillingDeferralStatus.APPROVED, DispenseBillingDeferralStatus.EXECUTED),
                        from, to)) {
            if (cover.getCreditLimit() == null || excluded.equals(cover.getPrescriptionId())
                    || claimed(cover.getDispenseId()) || cancelled(cover.getDispenseId())) {
                continue;
            }
            used = used.add(cover.getCreditLimit());
        }
        return used;
    }

    /** A fill cancelled after it left (returned, voided) owes the payer nothing. */
    private boolean cancelled(UUID dispenseId) {
        return dispenseId != null && dispenseRepository.findById(dispenseId)
                .map(fill -> fill.getStatus() == com.example.hms.enums.DispenseStatus.CANCELLED)
                .orElse(false);
    }

    private boolean claimed(UUID dispenseId) {
        return dispenseId != null
                && claimRepository.existsByDispenseIdAndClaimStatusNot(dispenseId, PharmacyClaimStatus.REJECTED);
    }
}
