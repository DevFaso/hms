package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * QA D3985 (r77): what a patient still owes the pharmacy of a hospital on the fills that left « à facturer » (a
 * credit, a cover or an urgency hand-over executed and not yet settled), for the desk's balance. The same figure the
 * deferrals page shows as « sorties, encore dues » ({@link DeferredFillBalance}); an inpatient item charged to the
 * open stay is billed on the stay's invoice and is not counted here (it would be counted twice).
 */
@Component
@RequiredArgsConstructor
public class PatientPharmacyCredit {

    private final DispenseBillingDeferralRepository deferralRepository;
    private final DeferredFillBalance fillBalance;

    /** The fills still due and their total, never below zero; nothing for a missing patient or hospital. */
    public record Due(int count, BigDecimal amount) {
        public static final Due NONE = new Due(0, BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public Due of(UUID patientId, UUID hospitalId) {
        if (patientId == null || hospitalId == null) {
            return Due.NONE;
        }
        List<DispenseBillingDeferral> executed = deferralRepository.findByHospitalIdAndPatientIdAndStatus(hospitalId,
            patientId, DispenseBillingDeferralStatus.EXECUTED);
        if (executed == null || executed.isEmpty()) {
            return Due.NONE;
        }
        int count = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (DispenseBillingDeferral deferral : executed) {
            if (deferral.getKind() == DispenseBillingDeferralKind.ADMISSION_ACCOUNT
                    || !hospitalId.equals(deferral.getHospitalId())) {
                continue;
            }
            BigDecimal due = fillBalance.stillDue(deferral);
            if (due != null && due.signum() > 0) {
                count++;
                amount = amount.add(due);
            }
        }
        return new Due(count, amount);
    }
}
