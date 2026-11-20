package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Policy rule 6 (provisional, V345): what the fills that left on a deferral still owe NOW, for the deferrals page
 * (« sorties, encore dues »). The same arithmetic as the counter's gate and the reconciliation: the charge, less the
 * payments posted, less the claims a money desk other than the dispenser stands behind ({@link FillClaimCover}). A
 * cancelled fill owes nothing; a fill of another hospital is never read.
 */
@Component
@RequiredArgsConstructor
public class DeferredFillBalance {

    private final DispenseRepository dispenseRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final PharmacyPaymentRepository paymentRepository;
    private final PharmacyClaimRepository claimRepository;

    @Transactional(readOnly = true)
    public BigDecimal stillDue(DispenseBillingDeferral deferral) {
        BigDecimal due = BigDecimal.ZERO;
        if (deferral == null || deferral.getId() == null) {
            return due;
        }
        for (Dispense fill : dispenseRepository.findByBillingDeferralId(deferral.getId())) {
            if (fill.getStatus() == DispenseStatus.CANCELLED || !atHospital(fill, deferral.getHospitalId())) {
                continue;
            }
            BigDecimal charge = DispenseCharge.of(fill, inventoryItemRepository).orElse(BigDecimal.ZERO);
            UUID handedOverBy = fill.getDispensedByUser() != null ? fill.getDispensedByUser().getId() : null;
            BigDecimal covered = Objects.requireNonNullElse(paymentRepository.sumPostedForDispense(fill.getId()),
                    BigDecimal.ZERO).add(FillClaimCover.coveredFor(claimRepository, fill.getId(), handedOverBy));
            due = due.add(charge.subtract(covered).max(BigDecimal.ZERO));
        }
        return due;
    }

    private static boolean atHospital(Dispense fill, UUID hospitalId) {
        return fill.getPharmacy() != null && fill.getPharmacy().getHospital() != null
                && Objects.equals(fill.getPharmacy().getHospital().getId(), hospitalId);
    }
}
