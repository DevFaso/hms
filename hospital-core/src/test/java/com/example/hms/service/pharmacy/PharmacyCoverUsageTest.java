package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Review of #1284: the pharmacy insurer shares of a year, each fill counted once, the order asked about left out. */
class PharmacyCoverUsageTest {

    private final PharmacyClaimRepository claims = mock(PharmacyClaimRepository.class);
    private final DispenseBillingDeferralRepository deferrals = mock(DispenseBillingDeferralRepository.class);
    private final PharmacyCoverUsage usage = new PharmacyCoverUsage(claims, deferrals);
    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    private DispenseBillingDeferral cover(String share, UUID prescriptionId, UUID dispenseId) {
        DispenseBillingDeferral row = DispenseBillingDeferral.builder().hospitalId(hospitalId).patientId(patientId)
            .prescriptionId(prescriptionId).dispenseId(dispenseId).kind(DispenseBillingDeferralKind.THIRD_PARTY_COVER)
            .status(DispenseBillingDeferralStatus.APPROVED).creditLimit(new BigDecimal(share)).build();
        row.setId(UUID.randomUUID());
        return row;
    }

    @Test
    @DisplayName("claims, plus covers whose fill has no live claim yet, less the order asked about")
    void eachFillCountsOnce() {
        UUID asked = UUID.randomUUID();
        UUID claimedFill = UUID.randomUUID();
        when(claims.sumLiveForPatientBetween(eq(patientId), eq(hospitalId), any(), any(), eq(asked)))
            .thenReturn(new BigDecimal("1170"));
        when(claims.existsByDispenseIdAndClaimStatusNot(claimedFill, PharmacyClaimStatus.REJECTED)).thenReturn(true);
        when(deferrals.findByHospitalIdAndPatientIdAndKindAndStatusInAndRequestedAtBetween(eq(hospitalId),
            eq(patientId), eq(DispenseBillingDeferralKind.THIRD_PARTY_COVER), any(), any(), any()))
            .thenReturn(List.of(cover("1170", UUID.randomUUID(), claimedFill), cover("750", UUID.randomUUID(), null),
                cover("900", asked, null)));

        assertThat(usage.usedInYear(patientId, hospitalId, 2026, asked)).isEqualByComparingTo("1920");
        assertThat(usage.usedInYear(null, hospitalId, 2026, asked)).isEqualByComparingTo("0");
    }
}
