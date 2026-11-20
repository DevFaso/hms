package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.enums.PharmacyPaymentStatus;
import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.enums.PharmacySaleStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.model.pharmacy.PharmacyRefund;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.model.pharmacy.SaleLine;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.PharmacyReconciliationDTO;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacyRefundRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D2673 (r65), billing plan PR 13 acceptance: a day with 3 dispenses, 2 counter sales and 1 refund reconciles to
 * no difference; a dispense whose stock movement is missing is listed; controlled balances equal the register.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PharmacyReconciliationService (QA D2673)")
class PharmacyReconciliationServiceTest {

    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private PharmacySaleRepository saleRepository;
    @Mock private DispenseRepository dispenseRepository;
    @Mock private PharmacyPaymentRepository paymentRepository;
    @Mock private PharmacyClaimRepository claimRepository;
    @Mock private PharmacyRefundRepository refundRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private com.example.hms.repository.pharmacy.PharmacyPriceChangeRepository priceChangeRepository;
    @Mock private RoleValidator roleValidator;

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC);
    private PharmacyReconciliationService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Pharmacy pharmacy;
    private InventoryItem paracetamol;
    private InventoryItem morphine;
    private StockLot lot;
    private StockLot morphineLot;
    private final List<PharmacySale> sales = new ArrayList<>();
    private final List<Dispense> fills = new ArrayList<>();
    private final List<PharmacyPayment> payments = new ArrayList<>();
    private final List<PharmacyRefund> refunds = new ArrayList<>();
    private final List<StockTransaction> movements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new PharmacyReconciliationService(pharmacyRepository, saleRepository, dispenseRepository,
                paymentRepository, claimRepository, refundRepository, stockTransactionRepository, stockLotRepository,
                inventoryItemRepository, new PharmacyPriceHistory(priceChangeRepository), roleValidator, clock);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        pharmacy = Pharmacy.builder().hospital(hospital).name("Pharmacie centrale").build();
        pharmacy.setId(UUID.randomUUID());

        MedicationCatalogItem para = MedicationCatalogItem.builder().nameFr("Paracétamol").hospital(hospital).build();
        para.setId(UUID.randomUUID());
        paracetamol = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(para)
                .unitPrice(new BigDecimal("250")).unit("comprimé").build();
        paracetamol.setId(UUID.randomUUID());
        lot = StockLot.builder().inventoryItem(paracetamol).lotNumber("LOT-A-R65-1").build();
        lot.setId(UUID.randomUUID());

        MedicationCatalogItem morph = MedicationCatalogItem.builder().nameFr("Morphine").hospital(hospital)
                .controlled(true).build();
        morph.setId(UUID.randomUUID());
        morphine = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(morph).unit("ampoule").build();
        morphine.setId(UUID.randomUUID());
        morphineLot = StockLot.builder().inventoryItem(morphine).lotNumber("LOT-MORPH-R65")
                .remainingQuantity(new BigDecimal("18")).build();
        morphineLot.setId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(roleValidator.canReadPharmacyReconciliation(userId, hospitalId)).thenReturn(true);
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacy.getId(), hospitalId)).thenReturn(Optional.of(pharmacy));
        when(inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), para.getId()))
                .thenReturn(Optional.of(paracetamol));
        when(inventoryItemRepository.findAllWithCatalogItemByPharmacyId(pharmacy.getId()))
                .thenReturn(List.of(paracetamol, morphine));
        when(stockLotRepository.existsByInventoryItemId(morphine.getId())).thenReturn(true);
        when(stockLotRepository.findAllByInventoryItemIdOrderByExpiryDateAsc(morphine.getId()))
                .thenReturn(List.of(morphineLot));
        // The register: +20 received, -2 dispensed = 18, as the lot holds.
        List<Object[]> register = new ArrayList<>();
        register.add(new Object[] {morphine.getId(), StockTransactionType.RECEIPT, new BigDecimal("20")});
        register.add(new Object[] {morphine.getId(), StockTransactionType.DISPENSE, new BigDecimal("2")});
        when(stockTransactionRepository.sumByItemAndTypeBefore(anyCollection(), any())).thenReturn(register);

        when(saleRepository.findForDay(eq(hospitalId), eq(pharmacy.getId()), any(), any())).thenReturn(sales);
        when(dispenseRepository.findCreatedForDay(eq(pharmacy.getId()), any(), any())).thenReturn(fills);
        when(dispenseRepository.findHandedOverForDay(eq(pharmacy.getId()), any(), any())).thenReturn(fills);
        when(paymentRepository.findPostedForPharmacyDay(eq(hospitalId), eq(pharmacy.getId()), any(), any()))
                .thenReturn(payments);
        when(refundRepository.findPaidBetween(eq(hospitalId), eq(pharmacy.getId()), any(), any())).thenReturn(refunds);
        when(stockTransactionRepository.findForDay(eq(pharmacy.getId()), any(), any())).thenReturn(movements);
        when(claimRepository.findByDispenseIdAndClaimStatusIn(any(), any())).thenReturn(List.of());
    }

    /** Policy rule 6 (review of #1244): a claim sent by a billing specialist of the hospital covers the fill. */
    private UUID aClaimSentBy(Dispense fill, String amount, boolean moneyDesk) {
        UUID sender = UUID.randomUUID();
        com.example.hms.model.User submitter = new com.example.hms.model.User();
        submitter.setId(sender);
        when(roleValidator.vouchesForClaimCover(sender, hospitalId)).thenReturn(moneyDesk);
        when(claimRepository.findByDispenseIdAndClaimStatusIn(eq(fill.getId()), any())).thenReturn(List.of(
                com.example.hms.model.pharmacy.PharmacyClaim.builder()
                        .claimStatus(com.example.hms.enums.PharmacyClaimStatus.SUBMITTED)
                        .amount(new BigDecimal(amount)).submittedByUser(submitter).build()));
        return sender;
    }

    private void sale(String quantity, PharmacyPaymentMethod method) {
        BigDecimal q = new BigDecimal(quantity);
        BigDecimal total = q.multiply(new BigDecimal("250"));
        PharmacySale s = PharmacySale.builder().pharmacy(pharmacy).paymentMethod(method)
                .status(PharmacySaleStatus.COMPLETED).totalAmount(total).saleDate(DAY.atTime(9, 0)).build();
        s.setId(UUID.randomUUID());
        s.addLine(SaleLine.builder().medicationCatalogItem(paracetamol.getMedicationCatalogItem()).stockLot(lot)
                .quantity(q).unitPrice(new BigDecimal("250")).lineTotal(total).build());
        sales.add(s);
        movements.add(StockTransaction.builder().inventoryItem(paracetamol).stockLot(lot)
                .transactionType(StockTransactionType.SALE).quantity(q).referenceId(s.getId()).build());
    }

    private Dispense fill(String quantity, boolean paid, boolean moved) {
        BigDecimal q = new BigDecimal(quantity);
        Dispense d = Dispense.builder().pharmacy(pharmacy).stockLot(lot)
                .medicationCatalogItem(paracetamol.getMedicationCatalogItem()).medicationName("Paracétamol")
                .quantityRequested(q).quantityDispensed(q).status(DispenseStatus.COMPLETED)
                .dispensedAt(DAY.atTime(10, 0)).build();
        d.setId(UUID.randomUUID());
        fills.add(d);
        BigDecimal charge = q.multiply(new BigDecimal("250"));
        if (paid) {
            PharmacyPayment p = PharmacyPayment.builder().dispense(d).amount(charge)
                    .paymentMethod(PharmacyPaymentMethod.CASH).status(PharmacyPaymentStatus.POSTED).build();
            payments.add(p);
        }
        when(paymentRepository.sumPostedForDispense(d.getId())).thenReturn(paid ? charge : BigDecimal.ZERO);
        if (moved) {
            movements.add(StockTransaction.builder().inventoryItem(paracetamol).stockLot(lot)
                    .transactionType(StockTransactionType.DISPENSE).quantity(q).build());
        }
        return d;
    }

    private void refund(String amount, String quantity) {
        PharmacyRefund r = PharmacyRefund.builder().amount(new BigDecimal(amount))
                .status(PharmacyRefundStatus.PAID).paidAt(DAY.atTime(16, 0)).build();
        refunds.add(r);
        movements.add(StockTransaction.builder().inventoryItem(paracetamol).stockLot(lot)
                .transactionType(StockTransactionType.RETURN).quantity(new BigDecimal(quantity)).build());
    }

    @Test
    @DisplayName("PR 13: 3 dispenses, 2 counter sales and 1 refund reconcile with no difference")
    void aReconciledDay() {
        fill("10", true, true);
        fill("20", true, true);
        fill("4", true, true);
        sale("10", PharmacyPaymentMethod.CASH);
        sale("2", PharmacyPaymentMethod.MOBILE_MONEY);
        refund("500", "2");

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);

        assertThat(day.getDifferences()).isEmpty();
        assertThat(day.getMoney().getSalesTotal()).isEqualByComparingTo("3000");
        assertThat(day.getMoney().getSalesByMethod()).containsKeys("CASH", "MOBILE_MONEY");
        assertThat(day.getMoney().getDispensePaymentsTotal()).isEqualByComparingTo("8500");
        assertThat(day.getMoney().getRefundsPaid()).isEqualByComparingTo("500");
        assertThat(day.getMoney().getNetCollected()).isEqualByComparingTo("11000");
        assertThat(day.getStock()).singleElement().satisfies(line -> {
            assertThat(line.getSoldMoved()).isEqualByComparingTo("12");
            assertThat(line.getDispensedMoved()).isEqualByComparingTo("34");
            assertThat(line.getReturned()).isEqualByComparingTo("2");
            assertThat(line.isDifference()).isFalse();
        });
    }

    @Test
    @DisplayName("PR 13: a dispense whose stock movement is missing is listed as a difference")
    void aMissingDispenseMovementIsListed() {
        fill("10", true, true);
        fill("20", true, false);

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);

        assertThat(day.getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("DISPENSE_STOCK_MISMATCH");
            assertThat(d.getExpected()).isEqualByComparingTo("30");
            assertThat(d.getActual()).isEqualByComparingTo("10");
            assertThat(d.getLotNumber()).isEqualTo("LOT-A-R65-1");
        });
    }

    @Test
    @DisplayName("QA D2670: a counter sale that moved no stock (as before the fix) is listed")
    void aSaleWithoutMovementIsListed() {
        sale("10", PharmacyPaymentMethod.CASH);
        movements.clear();

        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences())
                .extracting(PharmacyReconciliationDTO.Difference::getType).containsExactly("SALE_STOCK_MISMATCH");
    }

    @Test
    @DisplayName("QA D2671: a sale line priced away from the list (1 F CFA for 250) is listed with the shortfall")
    void aSaleOffTheListIsListed() {
        sale("10", PharmacyPaymentMethod.CASH);
        sales.get(0).getLines().get(0).setUnitPrice(BigDecimal.ONE);

        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("PRICE_NOT_LIST");
            assertThat(d.getAmount()).isEqualByComparingTo("2490");
        });
    }

    @Test
    @DisplayName("QA D2675: an unpaid hand-over is a difference; one « à facturer » is listed apart, not a difference")
    void unpaidAndDeferredHandOvers() {
        fill("200", false, true);
        Dispense deferred = fill("2", false, true);
        deferred.setBillingDeferredReason("Hospitalisé, facture du séjour");

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);

        assertThat(day.getDifferences()).extracting(PharmacyReconciliationDTO.Difference::getType)
                .containsExactly("UNPAID_HANDED_OVER");
        assertThat(day.getMoney().getUnpaidTotal()).isEqualByComparingTo("50000");
        assertThat(day.getMoney().getDeferredTotal()).isEqualByComparingTo("500");
        assertThat(day.getDeferred()).singleElement().extracting(PharmacyReconciliationDTO.DeferredFill::getReason)
                .isEqualTo("Hospitalisé, facture du séjour");
    }

    @Test
    @DisplayName("review of #1244: a claim the pharmacy sent alone covers nothing; the fill stays listed with what it owes")
    void aClaimThePharmacySentAloneIsNotCover() {
        Dispense unvouched = fill("4", false, true);
        aClaimSentBy(unvouched, "1000", false);
        com.example.hms.model.User dispenser = new com.example.hms.model.User();
        dispenser.setId(UUID.randomUUID());
        unvouched.setDispensedByUser(dispenser);
        UUID overrideId = UUID.randomUUID();
        unvouched.setBillingDeferralId(overrideId);
        unvouched.setBillingDeferredReason("Crise, caisse fermée");
        com.example.hms.model.Patient patient = new com.example.hms.model.Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Traoré");
        unvouched.setPatient(patient);
        DispenseBillingDeferralService deferrals = mock(DispenseBillingDeferralService.class);
        UUID stayId = UUID.randomUUID();
        when(deferrals.byIds(eq(hospitalId), org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.Map.of(
                overrideId, com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO.builder()
                        .id(overrideId).kind("URGENCY_OVERRIDE").admissionId(stayId).build()));
        service.setBillingDeferrals(deferrals);

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);

        assertThat(day.getDeferred()).singleElement().satisfies(f -> {
            assertThat(f.getDue()).as("the claim covers nothing").isEqualByComparingTo("1000");
            assertThat(f.getKind()).isEqualTo("URGENCY_OVERRIDE");
            assertThat(f.getPatientName()).isEqualTo("Awa Traoré");
            assertThat(f.getAdmissionId()).isEqualTo(stayId);
        });
        String csv = new String(service.exportCsv(pharmacy.getId(), DAY), StandardCharsets.UTF_8);
        assertThat(csv).contains("patient", "dossier", "sejour", "Awa Traoré", stayId.toString());
    }

    @Test
    @DisplayName("policy rule 6: every « à facturer » fill names the deferral behind it; one marked before the rule is UNAPPROVED")
    void deferredFillsNameTheirDeferral() {
        Dispense legacy = fill("2", false, true);
        legacy.setBillingDeferredReason("Hospitalisé, facture du séjour");
        Dispense override = fill("4", false, true);
        UUID overrideId = UUID.randomUUID();
        override.setBillingDeferralId(overrideId);
        override.setBillingDeferredReason("Crise, caisse fermée");
        Dispense inpatient = fill("6", false, true);
        UUID stayId = UUID.randomUUID();
        inpatient.setBillingDeferralId(stayId);
        DispenseBillingDeferralService deferrals = mock(DispenseBillingDeferralService.class);
        when(deferrals.byIds(org.mockito.ArgumentMatchers.eq(hospitalId), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(java.util.Map.of(
                        overrideId, com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO.builder()
                                .id(overrideId).kind("URGENCY_OVERRIDE").requestedByName("Awa Pharmacienne")
                                .executedByName("Awa Pharmacienne").build(),
                        stayId, com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO.builder()
                                .id(stayId).kind("ADMISSION_ACCOUNT").executedByName("Awa Pharmacienne").build()));
        service.setBillingDeferrals(deferrals);

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);

        assertThat(day.getDifferences()).as("a deferred fill is listed apart, never a difference").isEmpty();
        assertThat(day.getDeferred()).extracting(PharmacyReconciliationDTO.DeferredFill::getKind)
                .containsExactlyInAnyOrder("UNAPPROVED", "URGENCY_OVERRIDE", "ADMISSION_ACCOUNT");
        assertThat(day.getDeferred()).filteredOn(f -> "URGENCY_OVERRIDE".equals(f.getKind())).singleElement()
                .satisfies(f -> {
                    assertThat(f.getReviewed()).as("not reviewed yet").isFalse();
                    assertThat(f.getRequestedByName()).isEqualTo("Awa Pharmacienne");
                    assertThat(f.getApprovedByName()).isNull();
                });
        assertThat(day.getMoney().getDeferredTotal()).isEqualByComparingTo("3000");
        assertThat(day.getMoney().getUrgencyOverrideTotal()).isEqualByComparingTo("1000");
        assertThat(day.getMoney().getAdmissionAccountTotal()).isEqualByComparingTo("1500");

        String csv = new String(service.exportCsv(pharmacy.getId(), DAY), StandardCharsets.UTF_8);
        assertThat(csv).contains("a_facturer_urgence", "a_facturer_compte_hospitalisation", "URGENCY_OVERRIDE",
                "ADMISSION_ACCOUNT", "UNAPPROVED", "Awa Pharmacienne", "demande_par", "approuve_par");
    }

    @Test
    @DisplayName("PR 13: controlled balances equal the register; a lot that drifted is a difference")
    void controlledBalances() {
        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);
        assertThat(day.getControlled()).singleElement().satisfies(c -> {
            assertThat(c.getRegisterBalance()).isEqualByComparingTo("18");
            assertThat(c.isMatches()).isTrue();
        });

        morphineLot.setRemainingQuantity(new BigDecimal("17"));
        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences())
                .extracting(PharmacyReconciliationDTO.Difference::getType).containsExactly("CONTROLLED_BALANCE");
    }

    @Test
    @DisplayName("a reader without the role is refused; another hospital's pharmacy answers as missing")
    void scope() {
        when(roleValidator.canReadPharmacyReconciliation(userId, hospitalId)).thenReturn(false);
        UUID pharmacyId = pharmacy.getId();
        assertThatThrownBy(() -> service.daily(pharmacyId, DAY))
                .isInstanceOf(ClientSafeAccessDeniedException.class);

        when(roleValidator.canReadPharmacyReconciliation(userId, hospitalId)).thenReturn(true);
        UUID foreign = UUID.randomUUID();
        when(pharmacyRepository.findByIdAndHospital_Id(foreign, hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.daily(foreign, DAY)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the export neutralises a formula in a cell (CSV injection)")
    void exportNeutralisesFormulas() {
        sale("1", PharmacyPaymentMethod.CASH);
        paracetamol.getMedicationCatalogItem().setNameFr("=HYPERLINK(\"x\")");

        String csv = new String(service.exportCsv(pharmacy.getId(), DAY), StandardCharsets.UTF_8);

        assertThat(csv).doesNotContain(";\"=HYPERLINK").contains("ventes_comptoir");
    }

    @Test
    @DisplayName("the day defaults to today on the server clock")
    void defaultDay() {
        assertThat(service.daily(pharmacy.getId(), null).getDate()).isEqualTo(LocalDate.now(clock));
        assertThat(service.daily(pharmacy.getId(), null).getGeneratedAt()).isEqualTo(LocalDateTime.now(clock));
    }

    @Test
    @DisplayName("the picker lists the acting hospital's pharmacies, for a reader only")
    void pharmaciesForTheReader() {
        when(pharmacyRepository.findByHospitalIdAndActiveTrueOrderByNameAsc(hospitalId)).thenReturn(List.of(pharmacy));

        assertThat(service.pharmacies()).singleElement().satisfies(p -> {
            assertThat(p.getPharmacyId()).isEqualTo(pharmacy.getId());
            assertThat(p.getPharmacyName()).isEqualTo("Pharmacie centrale");
        });
        assertThat(service.dayOf(DAY)).isEqualTo(DAY);
    }

    @Test
    @DisplayName("a sale line with no lot is counted on its item; the export lists money, stock and differences")
    void unlottedSaleAndFullExport() {
        sale("3", PharmacyPaymentMethod.CASH);
        sales.get(0).getLines().get(0).setStockLot(null);
        movements.clear();
        fill("2", true, false);
        refund("250", "1");

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);
        assertThat(day.getStock()).extracting(PharmacyReconciliationDTO.StockLine::getLotNumber)
                .contains((String) null, "LOT-A-R65-1");
        assertThat(day.getDifferences()).extracting(PharmacyReconciliationDTO.Difference::getType)
                .contains("SALE_STOCK_MISMATCH", "DISPENSE_STOCK_MISMATCH");

        String csv = new String(service.exportCsv(pharmacy.getId(), DAY), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿").contains("ventes_CASH", "paiements_CASH", "remboursements",
                "SALE_STOCK_MISMATCH", "LOT-A-R65-1", "oui");
    }

    @Test
    @DisplayName("a controlled item kept without lots compares the register with its stock on hand")
    void controlledWithoutLots() {
        when(stockLotRepository.existsByInventoryItemId(morphine.getId())).thenReturn(false);
        morphine.setQuantityOnHand(new BigDecimal("18"));

        assertThat(service.daily(pharmacy.getId(), DAY).getControlled()).singleElement()
                .satisfies(c -> assertThat(c.isMatches()).isTrue());
    }

    @Test
    @DisplayName("a fill with no price, or fully covered by its claim, owes nothing at hand-over")
    void unpricedOrCoveredFillsOweNothing() {
        Dispense covered = fill("4", false, true);
        aClaimSentBy(covered, "1000", true);
        Dispense unpriced = fill("1", false, true);
        unpriced.setMedicationCatalogItem(null);

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);

        assertThat(day.getDifferences()).isEmpty();
        assertThat(day.getMoney().getDispenseCharges()).isEqualByComparingTo("1000");
    }

    // ───────────────────────── review of #1239 ─────────────────────────

    @Test
    @DisplayName("review of #1239: a DRAFT claim covers nothing; the fill handed over on it is an unpaid hand-over")
    void aDraftClaimIsNotCoverage() {
        Dispense fill = fill("4", false, true);
        // The claim exists (it bounds what may be claimed) but was never sent to the payer.
        when(claimRepository.sumLiveForDispense(fill.getId())).thenReturn(new BigDecimal("1000"));

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);

        assertThat(day.getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("UNPAID_HANDED_OVER");
            assertThat(d.getAmount()).isEqualByComparingTo("1000");
        });
    }

    private void priceChanged(String oldPrice, String newPrice, LocalDateTime at) {
        com.example.hms.model.pharmacy.PharmacyPriceChange change =
                com.example.hms.model.pharmacy.PharmacyPriceChange.builder().inventoryItemId(paracetamol.getId())
                        .oldPrice(new BigDecimal(oldPrice)).newPrice(new BigDecimal(newPrice)).changedAt(at)
                        .reason("Nouveau tarif fournisseur").build();
        paracetamol.setUnitPrice(new BigDecimal(newPrice));
        when(priceChangeRepository
                .findFirstByInventoryItemIdAndChangedAtLessThanEqualOrderByChangedAtDescCreatedAtDesc(
                        eq(paracetamol.getId()), any()))
                .thenAnswer(inv -> ((LocalDateTime) inv.getArgument(1)).isBefore(at)
                        ? Optional.empty() : Optional.of(change));
        when(priceChangeRepository
                .findFirstByInventoryItemIdAndChangedAtGreaterThanOrderByChangedAtAscCreatedAtAsc(
                        eq(paracetamol.getId()), any()))
                .thenAnswer(inv -> ((LocalDateTime) inv.getArgument(1)).isBefore(at)
                        ? Optional.of(change) : Optional.empty());
    }

    @Test
    @DisplayName("review of #1239: a sale is compared with the price in force when it was made, not with today's")
    void aSaleIsComparedWithThePriceOfItsDay() {
        // Sold at 9:00 at the 250 of that morning; the price went to 300 at noon.
        sale("10", PharmacyPaymentMethod.CASH);
        priceChanged("250", "300", DAY.atTime(12, 0));

        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences())
                .as("250 was the list price at 9:00").isEmpty();

        // A sale made AFTER the change and still at 250 is 50 a unit under the list.
        sales.get(0).setSaleDate(DAY.atTime(13, 0));
        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("PRICE_NOT_LIST");
            assertThat(d.getExpected()).isEqualByComparingTo("300");
            assertThat(d.getAmount()).isEqualByComparingTo("500");
        });
    }

    private void ledger(LocalDateTime before, StockTransactionType type, String quantity) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {lot.getId(), type, new BigDecimal(quantity)});
        when(stockTransactionRepository.sumByLotAndTypeBefore(pharmacy.getId(), before)).thenReturn(rows);
    }

    @Test
    @DisplayName("review of #1239: every lot's opening + movements is its closing; a lot that drifted is listed")
    void everyLotBalances() {
        when(stockLotRepository.findAllWithItemByPharmacyId(pharmacy.getId())).thenReturn(List.of(lot));
        // 100 received before the day; 12 sold during it (the ledger at closing: +100 -12).
        ledger(DAY.atStartOfDay(), StockTransactionType.RECEIPT, "100");
        List<Object[]> closing = new ArrayList<>();
        closing.add(new Object[] {lot.getId(), StockTransactionType.RECEIPT, new BigDecimal("100")});
        closing.add(new Object[] {lot.getId(), StockTransactionType.SALE, new BigDecimal("12")});
        when(stockTransactionRepository.sumByLotAndTypeBefore(pharmacy.getId(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(closing);
        lot.setRemainingQuantity(new BigDecimal("88"));

        PharmacyReconciliationDTO day = service.daily(pharmacy.getId(), DAY);
        assertThat(day.getDifferences()).isEmpty();
        assertThat(day.getLotBalances()).singleElement().satisfies(l -> {
            assertThat(l.getOpening()).isEqualByComparingTo("100");
            assertThat(l.getMovements()).isEqualByComparingTo("-12");
            assertThat(l.getClosing()).isEqualByComparingTo("88");
            assertThat(l.isMatches()).isTrue();
        });

        // 5 more left the lot since the day closed: its closing is what it holds now, less what moved since.
        List<Object[]> since = new ArrayList<>();
        since.add(new Object[] {lot.getId(), StockTransactionType.DISPENSE, new BigDecimal("5")});
        when(stockTransactionRepository.sumByLotAndTypeFrom(pharmacy.getId(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(since);
        lot.setRemainingQuantity(new BigDecimal("83"));
        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).isEmpty();

        // The lot holds 8 fewer than its movements explain (a quantity changed with no movement).
        lot.setRemainingQuantity(new BigDecimal("75"));
        PharmacyReconciliationDTO drifted = service.daily(pharmacy.getId(), DAY);
        assertThat(drifted.getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("LOT_BALANCE_MISMATCH");
            assertThat(d.getLotNumber()).isEqualTo("LOT-A-R65-1");
            assertThat(d.getExpected()).isEqualByComparingTo("88");
            assertThat(d.getActual()).isEqualByComparingTo("80");
        });
        assertThat(new String(service.exportCsv(pharmacy.getId(), DAY), StandardCharsets.UTF_8))
                .contains("solde_lot", "LOT_BALANCE_MISMATCH");
    }

    private StockTransaction patientReturn(UUID referenceId, String quantity) {
        StockTransaction ret = StockTransaction.builder().inventoryItem(paracetamol).stockLot(lot)
                .transactionType(StockTransactionType.RETURN).quantity(new BigDecimal(quantity))
                .referenceId(referenceId).build();
        ret.setId(UUID.randomUUID());
        movements.add(ret);
        return ret;
    }

    @Test
    @DisplayName("review of #1239: a patient return of a paid sale with no refund asked is listed with what it owes")
    void aReturnNobodyRefundedIsListed() {
        sale("10", PharmacyPaymentMethod.CASH);
        PharmacySale sold = sales.get(0);
        when(saleRepository.findByIdAndPharmacy_Id(sold.getId(), pharmacy.getId())).thenReturn(Optional.of(sold));
        StockTransaction ret = patientReturn(sold.getId(), "4");

        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("RETURN_NOT_REFUNDED");
            assertThat(d.getReferenceId()).isEqualTo(ret.getId());
            assertThat(d.getAmount()).as("4 returned at the 250 paid").isEqualByComparingTo("1000");
        });

        // Once a refund is asked (or decided) for that return, it is no longer a forgotten return.
        when(refundRepository.findRefundedReturnIds(anyCollection(), anyCollection())).thenReturn(List.of(ret.getId()));
        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).isEmpty();
    }

    @Test
    @DisplayName("review of #1239: a return of a paid fill is listed; the stock a cancelled fill puts back is not")
    void aReturnOfAFill() {
        Dispense paid = fill("10", true, true);
        when(dispenseRepository.findByIdAndPharmacy_Id(paid.getId(), pharmacy.getId())).thenReturn(Optional.of(paid));
        patientReturn(paid.getId(), "2");

        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("RETURN_NOT_REFUNDED");
            assertThat(d.getAmount()).as("2 500 paid / 10 x 2").isEqualByComparingTo("500");
        });

        paid.setStatus(DispenseStatus.CANCELLED);
        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences())
                .extracting(PharmacyReconciliationDTO.Difference::getType).doesNotContain("RETURN_NOT_REFUNDED");
    }

    @Test
    @DisplayName("review of #1239 (money back twice): refunds paid out above what is still counted as paid are listed")
    void aRefundAboveWhatIsStillPaidIsListed() {
        // 500 paid, 250 refunded and paid out, then the payment voided: 750 out for 500 in.
        Dispense fill = fill("2", false, true);
        fill.setStatus(DispenseStatus.CANCELLED);
        fills.clear();
        when(dispenseRepository.findByIdAndPharmacy_Id(fill.getId(), pharmacy.getId())).thenReturn(Optional.of(fill));
        when(refundRepository.findRefundedDispensesVoidedBetween(eq(hospitalId), eq(pharmacy.getId()), any(), any()))
                .thenReturn(List.of(fill.getId()));
        when(refundRepository.sumForDispense(eq(fill.getId()), anyCollection())).thenReturn(new BigDecimal("250"));
        movements.clear();

        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("REFUND_EXCEEDS_PAID");
            assertThat(d.getReferenceId()).isEqualTo(fill.getId());
            assertThat(d.getExpected()).isEqualByComparingTo("0");
            assertThat(d.getActual()).isEqualByComparingTo("250");
            assertThat(d.getAmount()).isEqualByComparingTo("250");
        });

        // The same refund on a payment still counted is no difference.
        when(paymentRepository.sumPostedForDispense(fill.getId())).thenReturn(new BigDecimal("500"));
        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).isEmpty();
    }

    @Test
    @DisplayName("review of #1239: a sale refunded above its total is listed on the day the refund is paid")
    void aSaleRefundedAboveItsTotalIsListed() {
        sale("2", PharmacyPaymentMethod.CASH);
        PharmacySale sold = sales.get(0);
        when(saleRepository.findByIdAndPharmacy_Id(sold.getId(), pharmacy.getId())).thenReturn(Optional.of(sold));
        refunds.add(PharmacyRefund.builder().saleId(sold.getId()).amount(new BigDecimal("600"))
                .status(PharmacyRefundStatus.PAID).paidAt(DAY.atTime(16, 0)).build());
        when(refundRepository.sumForSale(eq(sold.getId()), anyCollection())).thenReturn(new BigDecimal("600"));

        assertThat(service.daily(pharmacy.getId(), DAY).getDifferences()).singleElement().satisfies(d -> {
            assertThat(d.getType()).isEqualTo("REFUND_EXCEEDS_PAID");
            assertThat(d.getAmount()).as("600 out for 500 in").isEqualByComparingTo("100");
        });
    }
}
