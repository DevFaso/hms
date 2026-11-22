package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.service.billing.InvoiceCoverageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D3406 / D3408 (r70): one reader of what a fill costs and who pays which part: the prepared fill, the insurer
 * share by the patient's coverage, and the share a cover recorded or a live claim asks for.
 */
class FillCoverShareTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T09:00:00Z"), ZoneOffset.UTC);

    private final DispenseRepository dispenses = mock(DispenseRepository.class);
    private final DispenseBillingDeferralRepository deferrals = mock(DispenseBillingDeferralRepository.class);
    private final InventoryItemRepository inventory = mock(InventoryItemRepository.class);
    private final PharmacyPaymentRepository payments = mock(PharmacyPaymentRepository.class);
    private final PharmacyClaimRepository claims = mock(PharmacyClaimRepository.class);
    private final InvoiceCoverageService coverage = mock(InvoiceCoverageService.class);
    private final FillCoverShare share = new FillCoverShare(dispenses, deferrals, inventory, payments, claims,
        coverage, CLOCK);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();
    private Dispense fill;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Pharmacy pharmacy = new Pharmacy();
        pharmacy.setId(UUID.randomUUID());
        pharmacy.setHospital(hospital);
        MedicationCatalogItem item = MedicationCatalogItem.builder().build();
        item.setId(UUID.randomUUID());
        Prescription prescription = new Prescription();
        prescription.setId(prescriptionId);
        fill = new Dispense();
        fill.setId(UUID.randomUUID());
        fill.setPharmacy(pharmacy);
        fill.setPrescription(prescription);
        fill.setMedicationCatalogItem(item);
        fill.setQuantityDispensed(new BigDecimal("20"));
        fill.setStatus(DispenseStatus.PENDING);
        com.example.hms.model.Patient patient = new com.example.hms.model.Patient();
        patient.setId(UUID.randomUUID());
        fill.setPatient(patient);
        InventoryItem stocked = InventoryItem.builder().unitPrice(new BigDecimal("78")).build();
        when(inventory.findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), item.getId()))
            .thenReturn(Optional.of(stocked));
        when(dispenses.findFirstByPrescription_IdAndStatus(prescriptionId, DispenseStatus.PENDING))
            .thenReturn(Optional.of(fill));
    }

    private DispenseBillingDeferral cover(DispenseBillingDeferralStatus status, String insurer) {
        DispenseBillingDeferral row = DispenseBillingDeferral.builder().hospitalId(hospitalId)
            .prescriptionId(prescriptionId).kind(DispenseBillingDeferralKind.THIRD_PARTY_COVER).status(status)
            .creditLimit(insurer == null ? null : new BigDecimal(insurer)).build();
        row.setId(UUID.randomUUID());
        return row;
    }

    @Test
    @DisplayName("the prepared fill: its charge (20 x 78 = 1 560), what was paid, and what is still due")
    void thePreparedFill() {
        when(payments.sumPostedForDispense(fill.getId())).thenReturn(new BigDecimal("390"));

        FillCoverShare.PreparedFill prepared = share.preparedFill(prescriptionId, hospitalId).orElseThrow();

        assertThat(prepared.charge()).isEqualByComparingTo("1560");
        assertThat(prepared.paid()).isEqualByComparingTo("390");
        assertThat(prepared.due()).isEqualByComparingTo("1170");
        assertThat(share.preparedFill(prescriptionId, UUID.randomUUID())).as("another hospital's fill").isEmpty();
        assertThat(share.preparedFill(null, hospitalId)).isEmpty();
    }

    @Test
    @DisplayName("the plan share is the invoice rule on the charge, today; none without a plan coverage")
    void thePlanShare() {
        UUID patientId = UUID.randomUUID();
        when(coverage.quoteForPrescription(patientId, hospitalId, LocalDate.of(2026, 11, 22), new BigDecimal("1560"),
            prescriptionId))
            .thenReturn(Optional.of(new InvoiceCoverageService.CoverageQuote(UUID.randomUUID(), "Mutuelle Test A",
                "Formule", "P-1", null, null, 75, new BigDecimal("1560"), new BigDecimal("1170"),
                new BigDecimal("390"))));

        assertThat(share.planShare(patientId, hospitalId, new BigDecimal("1560"), prescriptionId))
            .contains(new BigDecimal("1170"));
        assertThat(share.planShare(UUID.randomUUID(), hospitalId, new BigDecimal("1560"), prescriptionId)).isEmpty();
        assertThat(share.planShare(patientId, null, BigDecimal.ONE, prescriptionId)).isEmpty();
    }

    /** The plan pays 75 % of whatever this fill costs. */
    private void planPaysThreeQuarters() {
        when(coverage.quoteForPrescription(any(), eq(hospitalId), any(), any(), any())).thenAnswer(call -> {
            BigDecimal charge = call.getArgument(3);
            BigDecimal payer = charge.multiply(new BigDecimal("0.75"));
            return Optional.of(new InvoiceCoverageService.CoverageQuote(UUID.randomUUID(), "Mutuelle Test A", "F",
                "P-1", null, null, 75, charge, payer, charge.subtract(payer)));
        });
    }

    @Test
    @DisplayName("review of #1284: the cover share is never more than the plan's share of THIS fill")
    void theCoverShareIsMeasuredOnThisFill() {
        planPaysThreeQuarters();
        // Approved on a larger fill (1 500); this fill costs 1 560 x 0.25 less: 20 x 78 = 1 560 -> plan 1 170.
        when(deferrals.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId),
            eq(prescriptionId), any())).thenReturn(List.of(cover(DispenseBillingDeferralStatus.APPROVED, "1500")));
        assertThat(share.coverShare(fill, hospitalId)).contains(new BigDecimal("1170.00"));
        // No plan any more: the insurer pays nothing of this fill.
        when(coverage.quoteForPrescription(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(share.coverShare(fill, hospitalId)).contains(BigDecimal.ZERO);
        assertThat(share.capOf(null, null, hospitalId, BigDecimal.TEN, prescriptionId)).isEqualByComparingTo("0");
        assertThat(share.capOf(BigDecimal.TEN, null, hospitalId, null, prescriptionId)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("the cover share: the one the fill left on, else the cover approved for its prescription")
    void theCoverShare() {
        planPaysThreeQuarters();
        DispenseBillingDeferral approved = cover(DispenseBillingDeferralStatus.APPROVED, "1170");
        when(deferrals.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId),
            eq(prescriptionId), any())).thenReturn(List.of(approved));
        assertThat(share.coverShare(fill, hospitalId)).contains(new BigDecimal("1170"));

        DispenseBillingDeferral executed = cover(DispenseBillingDeferralStatus.EXECUTED, "1000");
        fill.setBillingDeferralId(executed.getId());
        when(deferrals.findByHospitalIdAndIdIn(eq(hospitalId), any())).thenReturn(List.of(executed));
        assertThat(share.coverShare(fill, hospitalId)).contains(new BigDecimal("1000"));

        DispenseBillingDeferral credit = cover(DispenseBillingDeferralStatus.EXECUTED, "500");
        credit.setKind(DispenseBillingDeferralKind.OUTPATIENT_CREDIT);
        when(deferrals.findByHospitalIdAndIdIn(eq(hospitalId), any())).thenReturn(List.of(credit));
        assertThat(share.coverShare(fill, hospitalId)).as("a credit is no insurer share").isEmpty();
        assertThat(share.coverShare(null, hospitalId)).isEmpty();
    }

    @Test
    @DisplayName("the insurer share is the larger of a live claim and the cover's share; zero with neither")
    void theInsurerShare() {
        planPaysThreeQuarters();
        when(deferrals.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId),
            eq(prescriptionId), any())).thenReturn(List.of());
        assertThat(share.insurerShare(fill, hospitalId)).isEqualByComparingTo("0");

        when(claims.sumLiveForDispense(fill.getId())).thenReturn(new BigDecimal("1170"));
        assertThat(share.insurerShare(fill, hospitalId)).isEqualByComparingTo("1170");

        when(deferrals.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId),
            eq(prescriptionId), any())).thenReturn(List.of(cover(DispenseBillingDeferralStatus.APPROVED, "1200")));
        // The cover recorded 1 200, but the plan pays 1 170 of this fill: the larger share standing is the claim's.
        assertThat(share.insurerShare(fill, hospitalId)).isEqualByComparingTo("1170");
    }
}
