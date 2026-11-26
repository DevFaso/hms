package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** QA D3985 (r77): what a patient still owes the pharmacy on fills that left « à facturer », for the desk. */
class PatientPharmacyCreditTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private final DispenseBillingDeferralRepository deferrals = mock(DispenseBillingDeferralRepository.class);
    private final DeferredFillBalance balance = mock(DeferredFillBalance.class);
    private final PatientPharmacyCredit credit = new PatientPharmacyCredit(deferrals, balance);

    private DispenseBillingDeferral executed(DispenseBillingDeferralKind kind, UUID hospitalId, String due) {
        DispenseBillingDeferral deferral = DispenseBillingDeferral.builder().hospitalId(hospitalId)
            .patientId(PATIENT).kind(kind).status(DispenseBillingDeferralStatus.EXECUTED).build();
        deferral.setId(UUID.randomUUID());
        when(balance.stillDue(deferral)).thenReturn(new BigDecimal(due));
        return deferral;
    }

    @Test
    @DisplayName("the credits and covers still due are counted; a settled one, a stay item or another hospital's are not")
    void theFillsStillDue() {
        DispenseBillingDeferral credit1500 = executed(DispenseBillingDeferralKind.OUTPATIENT_CREDIT, HOSPITAL, "1500");
        DispenseBillingDeferral coverRemainder = executed(DispenseBillingDeferralKind.THIRD_PARTY_COVER, HOSPITAL,
            "250");
        DispenseBillingDeferral settled = executed(DispenseBillingDeferralKind.OUTPATIENT_CREDIT, HOSPITAL, "0");
        DispenseBillingDeferral stay = executed(DispenseBillingDeferralKind.ADMISSION_ACCOUNT, HOSPITAL, "900");
        DispenseBillingDeferral elsewhere = executed(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
            UUID.randomUUID(), "700");
        when(deferrals.findByHospitalIdAndPatientIdAndStatus(HOSPITAL, PATIENT, DispenseBillingDeferralStatus.EXECUTED))
            .thenReturn(List.of(credit1500, coverRemainder, settled, stay, elsewhere));

        PatientPharmacyCredit.Due due = credit.of(PATIENT, HOSPITAL);

        assertThat(due.count()).isEqualTo(2);
        assertThat(due.amount()).isEqualByComparingTo("1750");
    }

    @Test
    @DisplayName("nothing for a patient with no executed deferral, or a missing id")
    void nothingDue() {
        when(deferrals.findByHospitalIdAndPatientIdAndStatus(HOSPITAL, PATIENT, DispenseBillingDeferralStatus.EXECUTED))
            .thenReturn(List.of());
        assertThat(credit.of(PATIENT, HOSPITAL)).isEqualTo(PatientPharmacyCredit.Due.NONE);
        assertThat(credit.of(null, HOSPITAL)).isEqualTo(PatientPharmacyCredit.Due.NONE);
    }

    @Test
    @DisplayName("a missing hospital, a null answer or an unknown balance counts nothing")
    void nullAnswersCountNothing() {
        assertThat(credit.of(PATIENT, null)).isEqualTo(PatientPharmacyCredit.Due.NONE);
        when(deferrals.findByHospitalIdAndPatientIdAndStatus(HOSPITAL, PATIENT, DispenseBillingDeferralStatus.EXECUTED))
            .thenReturn(null);
        assertThat(credit.of(PATIENT, HOSPITAL)).isEqualTo(PatientPharmacyCredit.Due.NONE);

        DispenseBillingDeferral unknown = DispenseBillingDeferral.builder().hospitalId(HOSPITAL).patientId(PATIENT)
            .kind(DispenseBillingDeferralKind.URGENCY_OVERRIDE).status(DispenseBillingDeferralStatus.EXECUTED).build();
        unknown.setId(UUID.randomUUID());
        when(balance.stillDue(unknown)).thenReturn(null);
        DispenseBillingDeferral urgency = executed(DispenseBillingDeferralKind.URGENCY_OVERRIDE, HOSPITAL, "400");
        when(deferrals.findByHospitalIdAndPatientIdAndStatus(HOSPITAL, PATIENT, DispenseBillingDeferralStatus.EXECUTED))
            .thenReturn(List.of(unknown, urgency));

        PatientPharmacyCredit.Due due = credit.of(PATIENT, HOSPITAL);
        assertThat(due.count()).isEqualTo(1);
        assertThat(due.amount()).isEqualByComparingTo("400");
    }
}
