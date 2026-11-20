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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 */
@DisplayName("A claim covers a fill only when a money desk other than the dispenser stands behind it (review of #1244)")
class FillClaimCoverTest {

    private final PharmacyClaimRepository claims = mock(PharmacyClaimRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID dispenseId = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();
    private final UUID otherPharmacist = UUID.randomUUID();
    private final UUID billing = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(roles.vouchesForClaimCover(billing, hospitalId)).thenReturn(true);
    }

    private static User user(UUID id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private void theClaimIs(PharmacyClaimStatus status, UUID submittedBy, UUID decidedBy) {
        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of(
                PharmacyClaim.builder().claimStatus(status).amount(new BigDecimal("1000"))
                        .submittedByUser(submittedBy != null ? user(submittedBy) : null).decidedBy(decidedBy).build()));
    }

    private BigDecimal coverFor(UUID handingOver) {
        return FillClaimCover.coveredFor(claims, roles, dispenseId, hospitalId, handingOver);
    }

    @Test
    @DisplayName("the hole: a claim the pharmacist created and submitted alone covers nothing for them, or for a colleague")
    void aPharmacistsOwnClaimCoversNothing() {
        theClaimIs(PharmacyClaimStatus.SUBMITTED, pharmacist, null);

        assertThat(coverFor(pharmacist)).isEqualByComparingTo("0");
        assertThat(coverFor(otherPharmacist)).as("no money desk stands behind it").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a claim billing sent covers the fill, except for billing itself handing it over")
    void aClaimBillingSentCovers() {
        theClaimIs(PharmacyClaimStatus.SUBMITTED, billing, null);

        assertThat(coverFor(pharmacist)).isEqualByComparingTo("1000");
        assertThat(coverFor(billing)).as("never the same person").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an accepted or paid claim covers when a money desk recorded the answer, not when the pharmacy did")
    void whoRecordedThePayersAnswer() {
        theClaimIs(PharmacyClaimStatus.ACCEPTED, pharmacist, billing);
        assertThat(coverFor(pharmacist)).as("sent by the pharmacist, accepted by billing").isEqualByComparingTo("1000");

        theClaimIs(PharmacyClaimStatus.ACCEPTED, pharmacist, pharmacist);
        assertThat(coverFor(pharmacist)).as("sent and accepted by the dispenser").isEqualByComparingTo("0");
        assertThat(coverFor(otherPharmacist)).as("accepted by a pharmacist").isEqualByComparingTo("0");

        theClaimIs(PharmacyClaimStatus.PAID, billing, billing);
        assertThat(coverFor(pharmacist)).isEqualByComparingTo("1000");
        assertThat(coverFor(billing)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a claim accepted before anybody was recorded falls back on who sent it")
    void aClaimDecidedBeforeTheRule() {
        theClaimIs(PharmacyClaimStatus.ACCEPTED, pharmacist, null);
        assertThat(coverFor(otherPharmacist)).isEqualByComparingTo("0");

        theClaimIs(PharmacyClaimStatus.ACCEPTED, billing, null);
        assertThat(coverFor(pharmacist)).isEqualByComparingTo("1000");

        theClaimIs(PharmacyClaimStatus.SUBMITTED, null, null);
        assertThat(coverFor(pharmacist)).as("nobody known behind it").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a draft or a rejected claim never counts, whoever made it; no claim, no cover")
    void neverADraftOrARejectedClaim() {
        PharmacyClaim draft = PharmacyClaim.builder().claimStatus(PharmacyClaimStatus.DRAFT)
                .amount(BigDecimal.TEN).submittedByUser(user(billing)).build();
        PharmacyClaim rejected = PharmacyClaim.builder().claimStatus(PharmacyClaimStatus.REJECTED)
                .amount(BigDecimal.TEN).submittedByUser(user(billing)).decidedBy(billing).build();
        assertThat(FillClaimCover.counts(draft, roles, hospitalId, pharmacist)).isFalse();
        assertThat(FillClaimCover.counts(rejected, roles, hospitalId, pharmacist)).isFalse();

        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of());
        assertThat(coverFor(pharmacist)).isEqualByComparingTo("0");
        assertThat(FillClaimCover.coveredFor(null, roles, dispenseId, hospitalId, pharmacist)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("what the fills of a deferral still owe: charge less payments and vouched claims; a cancelled fill owes nothing")
    void whatADeferralStillOwes() {
        DispenseRepository dispenses = mock(DispenseRepository.class);
        InventoryItemRepository inventory = mock(InventoryItemRepository.class);
        PharmacyPaymentRepository payments = mock(PharmacyPaymentRepository.class);
        DeferredFillBalance balance = new DeferredFillBalance(dispenses, inventory, payments, claims, roles);

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

        // 10 units at 100 = 1 000; 300 paid; the pharmacist's own claim covers nothing.
        theClaimIs(PharmacyClaimStatus.SUBMITTED, pharmacist, null);
        assertThat(balance.stillDue(deferral)).isEqualByComparingTo("700");

        // Once billing stands behind a claim for the rest, nothing is due any more.
        when(claims.findByDispenseIdAndClaimStatusIn(eq(dispenseId), any())).thenReturn(List.of(
                PharmacyClaim.builder().claimStatus(PharmacyClaimStatus.SUBMITTED).amount(new BigDecimal("700"))
                        .submittedByUser(user(billing)).build()));
        assertThat(balance.stillDue(deferral)).isEqualByComparingTo("0");
        assertThat(balance.stillDue(null)).isEqualByComparingTo("0");
    }

    private static Dispense fill(Pharmacy pharmacy, MedicationCatalogItem medicine, DispenseStatus status) {
        Dispense d = Dispense.builder().pharmacy(pharmacy).medicationCatalogItem(medicine)
                .quantityRequested(BigDecimal.TEN).quantityDispensed(BigDecimal.TEN).status(status).build();
        d.setId(UUID.randomUUID());
        return d;
    }
}
