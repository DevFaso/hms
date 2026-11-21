package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Policy rule 6 (review of #1244, HIGH): a claim covers a fill at the counter only when somebody of the money desks,
 * other than the person handing the fill over, stands behind it. A pharmacist used to create and submit a claim for
 * the whole charge alone, and hand the fill over with nothing due.
 *
 * <p>Review round 2: who stands behind a claim is recorded when it happens ({@link ClaimVouching}) and never
 * rewritten; the cover reads only that, so a closed day does not move.
 */
@DisplayName("A claim covers a fill only when a money desk other than the dispenser stands behind it (review of #1244)")
class FillClaimCoverTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 10, 0);

    private final PharmacyClaimRepository claims = mock(PharmacyClaimRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID dispenseId = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();
    private final UUID otherPharmacist = UUID.randomUUID();
    private final UUID billing = UUID.randomUUID();
    private final UUID accountant = UUID.randomUUID();

    private static User user(UUID id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private PharmacyClaim claim(PharmacyClaimStatus status) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        return PharmacyClaim.builder().claimStatus(status).amount(new BigDecimal("1000")).hospital(hospital).build();
    }

    private void theClaimIs(PharmacyClaimStatus status, UUID vouchedBy, UUID secondVouchedBy) {
        PharmacyClaim claim = claim(status);
        claim.setVouchedBy(vouchedBy);
        claim.setSecondVouchedBy(secondVouchedBy);
        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of(claim));
    }

    private BigDecimal coverFor(UUID handingOver) {
        return FillClaimCover.coveredFor(claims, dispenseId, handingOver);
    }

    private void aMoneyDesk(UUID... people) {
        for (UUID person : people) {
            when(roles.vouchesForClaimCover(person, hospitalId)).thenReturn(true);
        }
    }

    @Test
    @DisplayName("the hole: a claim nobody of the money desks stands behind covers nothing, whatever its state")
    void aClaimOnlyThePharmacyTouchedCoversNothing() {
        for (PharmacyClaimStatus status : List.of(PharmacyClaimStatus.SUBMITTED, PharmacyClaimStatus.ACCEPTED,
                PharmacyClaimStatus.PAID)) {
            theClaimIs(status, null, null);
            assertThat(coverFor(pharmacist)).as(status.name()).isEqualByComparingTo("0");
            assertThat(coverFor(otherPharmacist)).as(status.name()).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("a claim billing stands behind covers the fill, except for billing itself handing it over")
    void aVouchedClaimCovers() {
        theClaimIs(PharmacyClaimStatus.SUBMITTED, billing, null);

        assertThat(coverFor(pharmacist)).isEqualByComparingTo("1000");
        assertThat(coverFor(billing)).as("never the same person").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("with two people behind a claim, whoever hands the fill over, somebody else stands behind it")
    void twoVouchers() {
        theClaimIs(PharmacyClaimStatus.ACCEPTED, billing, accountant);

        assertThat(coverFor(billing)).isEqualByComparingTo("1000");
        assertThat(coverFor(accountant)).isEqualByComparingTo("1000");
        assertThat(coverFor(pharmacist)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a draft or a rejected claim never counts, whoever stands behind it; no claim, no cover")
    void neverADraftOrARejectedClaim() {
        PharmacyClaim draft = claim(PharmacyClaimStatus.DRAFT);
        draft.setVouchedBy(billing);
        PharmacyClaim rejected = claim(PharmacyClaimStatus.REJECTED);
        rejected.setVouchedBy(billing);
        assertThat(FillClaimCover.counts(draft, pharmacist)).isFalse();
        assertThat(FillClaimCover.counts(rejected, pharmacist)).isFalse();

        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of());
        assertThat(coverFor(pharmacist)).isEqualByComparingTo("0");
        assertThat(FillClaimCover.coveredFor(null, dispenseId, pharmacist)).isEqualByComparingTo("0");
    }

    // ───────────────────── review round 2: the vouching is recorded when it happens ─────────────────────

    @Test
    @DisplayName("only somebody holding a money desk at that moment is recorded; the pharmacy records nothing")
    void onlyAMoneyDeskIsRecorded() {
        aMoneyDesk(billing);
        PharmacyClaim claim = claim(PharmacyClaimStatus.SUBMITTED);

        ClaimVouching.recordVoucher(claim, pharmacist, roles, AT);
        assertThat(claim.getVouchedBy()).as("a pharmacist sent it").isNull();
        ClaimVouching.recordVoucher(claim, null, roles, AT);
        assertThat(claim.getVouchedBy()).isNull();

        ClaimVouching.recordVoucher(claim, billing, roles, AT.plusHours(1));
        assertThat(claim.getVouchedBy()).isEqualTo(billing);
        assertThat(claim.getVouchedAt()).isEqualTo(AT.plusHours(1));
        assertThat(claim.getSecondVouchedBy()).isNull();
    }

    @Test
    @DisplayName("regression A: billing accepts, the pharmacist hands over, then records the payment: the cover stays")
    void aLaterStepByThePharmacyErasesNobody() {
        aMoneyDesk(billing);
        PharmacyClaim claim = claim(PharmacyClaimStatus.SUBMITTED);
        ClaimVouching.recordVoucher(claim, pharmacist, roles, AT);
        claim.setClaimStatus(PharmacyClaimStatus.ACCEPTED);
        ClaimVouching.recordVoucher(claim, billing, roles, AT.plusHours(1));
        assertThat(FillClaimCover.counts(claim, pharmacist)).isTrue();

        // The fill is handed over; days later the pharmacist records the insurer's payment.
        claim.setClaimStatus(PharmacyClaimStatus.PAID);
        ClaimVouching.recordVoucher(claim, pharmacist, roles, AT.plusDays(9));

        assertThat(claim.getVouchedBy()).as("billing still stands behind it").isEqualTo(billing);
        assertThat(claim.getVouchedAt()).isEqualTo(AT.plusHours(1));
        assertThat(claim.getSecondVouchedBy()).isNull();
        assertThat(FillClaimCover.counts(claim, pharmacist)).as("the closed day still reads covered").isTrue();
    }

    @Test
    @DisplayName("regression A, dual role: the person who handed over holds a money desk too and records the payment")
    void aLaterStepByTheHanderWithAMoneyDeskErasesNobody() {
        aMoneyDesk(billing, pharmacist);
        PharmacyClaim claim = claim(PharmacyClaimStatus.ACCEPTED);
        ClaimVouching.recordVoucher(claim, billing, roles, AT);
        claim.setClaimStatus(PharmacyClaimStatus.PAID);
        ClaimVouching.recordVoucher(claim, pharmacist, roles, AT.plusDays(9));
        ClaimVouching.recordVoucher(claim, accountant, roles, AT.plusDays(10));

        assertThat(claim.getVouchedBy()).isEqualTo(billing);
        assertThat(claim.getSecondVouchedBy()).as("the first other person, written once").isEqualTo(pharmacist);
        assertThat(claim.getSecondVouchedAt()).isEqualTo(AT.plusDays(9));
        assertThat(FillClaimCover.counts(claim, pharmacist)).isTrue();
    }

    @Test
    @DisplayName("regression B: the clerk's assignment ends afterwards: every fill they covered stays covered")
    void theVoucherLeavingChangesNothing() {
        aMoneyDesk(billing);
        PharmacyClaim claim = claim(PharmacyClaimStatus.SUBMITTED);
        ClaimVouching.recordVoucher(claim, billing, roles, AT);
        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of(claim));
        assertThat(coverFor(pharmacist)).isEqualByComparingTo("1000");

        // The clerk leaves the hospital: no role there any more. The cover asks nobody about today's roles.
        when(roles.vouchesForClaimCover(billing, hospitalId)).thenReturn(false);

        assertThat(coverFor(pharmacist)).isEqualByComparingTo("1000");
        // Nor does a step they repeat after leaving add or remove anything.
        ClaimVouching.recordVoucher(claim, billing, roles, AT.plusDays(30));
        assertThat(claim.getVouchedBy()).isEqualTo(billing);
        assertThat(claim.getVouchedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("the person who sent the claim, holding a money desk, hands over: an acceptance by somebody else counts")
    void theSenderHandsOverAfterSomebodyElseAccepted() {
        aMoneyDesk(billing, pharmacist);
        PharmacyClaim claim = claim(PharmacyClaimStatus.SUBMITTED);
        ClaimVouching.recordVoucher(claim, pharmacist, roles, AT);
        assertThat(FillClaimCover.counts(claim, pharmacist)).as("their own claim, alone").isFalse();

        claim.setClaimStatus(PharmacyClaimStatus.ACCEPTED);
        ClaimVouching.recordVoucher(claim, billing, roles, AT.plusHours(2));

        assertThat(FillClaimCover.counts(claim, pharmacist)).isTrue();
        assertThat(FillClaimCover.counts(claim, billing)).isTrue();
    }

    @Test
    @DisplayName("what the fills of a deferral still owe: charge less payments and vouched claims; a cancelled fill owes nothing")
    void whatADeferralStillOwes() {
        DispenseRepository dispenses = mock(DispenseRepository.class);
        InventoryItemRepository inventory = mock(InventoryItemRepository.class);
        PharmacyPaymentRepository payments = mock(PharmacyPaymentRepository.class);
        DeferredFillBalance balance = new DeferredFillBalance(dispenses, inventory, payments, claims);

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).name("Pharmacie").build();
        pharmacy.setId(UUID.randomUUID());
        MedicationCatalogItem medicine = new MedicationCatalogItem();
        medicine.setId(UUID.randomUUID());
        InventoryItem priced = new InventoryItem();
        priced.setUnitPrice(new BigDecimal("100"));
        when(inventory.findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), medicine.getId()))
                .thenReturn(Optional.of(priced));

        Dispense owing = fill(pharmacy, medicine, DispenseStatus.COMPLETED);
        owing.setId(dispenseId);
        owing.setDispensedByUser(user(pharmacist));
        Dispense cancelled = fill(pharmacy, medicine, DispenseStatus.CANCELLED);
        Hospital elsewhere = new Hospital();
        elsewhere.setId(UUID.randomUUID());
        Dispense foreign = fill(Pharmacy.builder().hospital(elsewhere).name("Autre").build(), medicine,
                DispenseStatus.COMPLETED);
        DispenseBillingDeferral deferral = DispenseBillingDeferral.builder().hospitalId(hospitalId).build();
        deferral.setId(UUID.randomUUID());
        when(dispenses.findByBillingDeferralId(deferral.getId())).thenReturn(List.of(owing, cancelled, foreign));
        when(payments.sumPostedForDispense(dispenseId)).thenReturn(new BigDecimal("300"));

        // 10 units at 100 = 1 000; 300 paid; a claim nobody of the money desks stands behind covers nothing.
        theClaimIs(PharmacyClaimStatus.SUBMITTED, null, null);
        assertThat(balance.stillDue(deferral)).isEqualByComparingTo("700");

        // Once billing stands behind a claim for the rest, nothing is due any more.
        PharmacyClaim vouched = claim(PharmacyClaimStatus.SUBMITTED);
        vouched.setAmount(new BigDecimal("700"));
        vouched.setVouchedBy(billing);
        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of(vouched));
        assertThat(balance.stillDue(deferral)).isEqualByComparingTo("0");
        assertThat(balance.stillDue(null)).isEqualByComparingTo("0");
    }

    private static Dispense fill(Pharmacy pharmacy, MedicationCatalogItem medicine, DispenseStatus status) {
        Dispense d = Dispense.builder().pharmacy(pharmacy).medicationCatalogItem(medicine)
                .quantityRequested(BigDecimal.TEN).quantityDispensed(BigDecimal.TEN).status(status).build();
        d.setId(UUID.randomUUID());
        return d;
    }

    @Test
    @DisplayName("QA D2931 (r66): why a claim sent does not cover: the person handing over stands behind it alone, or nobody does")
    void whyAClaimDoesNotCover() {
        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of());
        assertThat(FillClaimCover.gapFor(claims, dispenseId, pharmacist)).isEqualTo(FillClaimCover.Gap.NONE);

        theClaimIs(PharmacyClaimStatus.ACCEPTED, null, null);
        assertThat(FillClaimCover.gapFor(claims, dispenseId, pharmacist)).isEqualTo(FillClaimCover.Gap.NO_VOUCHER);

        theClaimIs(PharmacyClaimStatus.ACCEPTED, billing, null);
        assertThat(FillClaimCover.gapFor(claims, dispenseId, billing)).isEqualTo(FillClaimCover.Gap.OWN_VOUCHER);
        assertThat(FillClaimCover.gapFor(claims, dispenseId, pharmacist))
                .as("it covers for anybody else").isEqualTo(FillClaimCover.Gap.NONE);

        theClaimIs(PharmacyClaimStatus.ACCEPTED, billing, accountant);
        assertThat(FillClaimCover.gapFor(claims, dispenseId, billing)).isEqualTo(FillClaimCover.Gap.NONE);
        assertThat(FillClaimCover.gapFor(null, dispenseId, billing)).isEqualTo(FillClaimCover.Gap.NONE);
    }
}
