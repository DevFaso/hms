package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.*;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.repository.pharmacy.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockTransferBookingTest {
    @Mock PharmacyRepository pharmacies;
    @Mock InventoryItemRepository items;
    @Mock StockLotRepository lots;
    @Mock StockTransactionRepository transactions;
    @Mock EntityManager entityManager;
    @Mock FefoLotPolicy fefoLotPolicy;
    @Mock PharmacyPriceHistory priceHistory;
    @Mock com.example.hms.service.AuditEventLogService auditEventLogService;
    @InjectMocks StockTransferBooking booking;
    private Pharmacy source;
    private Pharmacy destination;
    private InventoryItem sourceItem;
    private InventoryItem destinationItem;
    private StockLot sourceLot;
    private StockTransaction outgoing;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        source = Pharmacy.builder().hospital(hospital).name("Source").active(true).build();
        source.setId(UUID.randomUUID());
        destination = Pharmacy.builder().hospital(hospital).name("Ward").active(true).build();
        destination.setId(UUID.randomUUID());
        MedicationCatalogItem medication = new MedicationCatalogItem();
        medication.setId(UUID.randomUUID());
        sourceItem = InventoryItem.builder().pharmacy(source).medicationCatalogItem(medication).unit("tablet")
            .unitPrice(new BigDecimal("500")).build();
        sourceItem.setId(UUID.randomUUID());
        destinationItem = InventoryItem.builder().pharmacy(destination).medicationCatalogItem(medication).unit("tablet").build();
        destinationItem.setId(UUID.randomUUID());
        lenient().when(items.tryLockById(destinationItem.getId())).thenReturn(Optional.of(destinationItem));
        lenient().when(items.tryLockById(sourceItem.getId())).thenReturn(Optional.of(sourceItem));
        sourceLot = StockLot.builder().inventoryItem(sourceItem).lotNumber("BATCH-77")
            .expiryDate(LocalDate.now().plusYears(1)).unitCost(new BigDecimal("25.00"))
            .supplier("Synthetic supplier").build();
        sourceLot.setId(UUID.randomUUID());
        outgoing = StockTransaction.builder().inventoryItem(sourceItem).stockLot(sourceLot)
            .quantity(BigDecimal.TEN).transactionType(StockTransactionType.TRANSFER)
            .referenceId(destination.getId()).reason("Ward replenishment").performedByUser(new User()).build();
        outgoing.setId(UUID.randomUUID());
    }

    @Test
    void transferBooksTheDestinationLotWithOriginalExpiryCostAndLinkedLedger() {
        destinationItem.setUnitPrice(new BigDecimal("375"));
        when(items.findByPharmacyIdAndMedicationCatalogItemId(destination.getId(), sourceItem.getMedicationCatalogItem().getId()))
            .thenReturn(Optional.of(destinationItem));
        when(lots.findSameLot(destinationItem.getId(), sourceLot.getLotNumber(), sourceLot.getExpiryDate())).thenReturn(List.of());
        when(lots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(items.incrementOnHand(eq(destinationItem.getId()), eq(BigDecimal.TEN), any())).thenReturn(1);

        booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing);

        ArgumentCaptor<StockTransaction> ledger = ArgumentCaptor.forClass(StockTransaction.class);
        verify(transactions).save(ledger.capture());
        StockTransaction received = ledger.getValue();
        assertThat(received.getTransactionType()).isEqualTo(StockTransactionType.RECEIPT);
        assertThat(received.getReferenceId()).isEqualTo(outgoing.getId());
        assertThat(received.getTransferOriginId()).isEqualTo(outgoing.getId());
        assertThat(received.getInventoryItem()).isSameAs(destinationItem);
        assertThat(received.getStockLot().getLotNumber()).isEqualTo(sourceLot.getLotNumber());
        assertThat(received.getStockLot().getExpiryDate()).isEqualTo(sourceLot.getExpiryDate());
        assertThat(received.getStockLot().getUnitCost()).isEqualByComparingTo(sourceLot.getUnitCost());
        assertThat(received.getStockLot().getInitialQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(received.getStockLot().getRemainingQuantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(destinationItem.getUnitPrice()).isEqualByComparingTo("375");
    }

    /** QA D4181 (r81): the syrup sold 900 F at the main pharmacy, moved to a store that never held it. */
    private void theSyrupMovesToAStoreThatNeverHeldIt() {
        sourceItem.setUnitPrice(new BigDecimal("900"));
        sourceItem.setUnit("flacon");
        User mover = new User();
        mover.setId(UUID.randomUUID());
        outgoing.setPerformedByUser(mover);
        when(items.findByPharmacyIdAndMedicationCatalogItemId(destination.getId(), sourceItem.getMedicationCatalogItem().getId()))
            .thenReturn(Optional.empty());
        lenient().when(items.findAllWithCatalogItemByPharmacyId(destination.getId())).thenReturn(List.of());
        when(items.saveAndFlush(any())).thenAnswer(call -> {
            InventoryItem saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(lots.findSameLot(any(), eq(sourceLot.getLotNumber()), eq(sourceLot.getExpiryDate()))).thenReturn(List.of());
        when(lots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(items.incrementOnHand(any(), eq(BigDecimal.TEN), any())).thenReturn(1);
    }

    @Test
    void theItemATransferCreatesIsSoldAtItsSourcePriceAndUnit() {
        theSyrupMovesToAStoreThatNeverHeldIt();

        booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing);

        ArgumentCaptor<StockTransaction> ledger = ArgumentCaptor.forClass(StockTransaction.class);
        verify(transactions).save(ledger.capture());
        InventoryItem created = ledger.getValue().getInventoryItem();
        assertThat(created.getPharmacy()).isSameAs(destination);
        assertThat(created.getUnit()).isEqualTo("flacon");
        assertThat(created.getUnitPrice()).as("the destination item is priced as its source").isEqualByComparingTo("900");
        assertThat(PharmacyListPrice.of(created)).contains(new BigDecimal("900"));
        verify(priceHistory).keep(eq(created), isNull(), eq(new BigDecimal("900")),
            eq(StockTransferBooking.TRANSFER_PRICE_REASON), eq(outgoing.getPerformedByUser().getId()), any());
        // Review of #1380 (F4): the same audit event as a price set on the item form.
        verify(auditEventLogService).logEvent(argThat(event ->
            event.getEventType() == com.example.hms.enums.AuditEventType.PHARMACY_PRICE_CHANGED
                && created.getId().toString().equals(event.getResourceId())));
    }

    @Test
    void aPriceOfZeroSetOnPurposeIsNeverOverwritten() {
        // Review of #1380 (F1): the store manager set the item at B from 500 to 0, with a reason in the history.
        sourceItem.setUnitPrice(new BigDecimal("500"));
        destinationItem.setUnitPrice(BigDecimal.ZERO);
        when(items.findByPharmacyIdAndMedicationCatalogItemId(destination.getId(), sourceItem.getMedicationCatalogItem().getId()))
            .thenReturn(Optional.of(destinationItem));
        when(lots.findSameLot(destinationItem.getId(), sourceLot.getLotNumber(), sourceLot.getExpiryDate())).thenReturn(List.of());
        when(lots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(items.incrementOnHand(eq(destinationItem.getId()), eq(BigDecimal.TEN), any())).thenReturn(1);

        booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing);

        assertThat(destinationItem.getUnitPrice()).as("0 is the store's own price").isEqualByComparingTo("0");
        verifyNoInteractions(priceHistory, auditEventLogService);
        verify(items, never()).saveAndFlush(destinationItem);
    }

    @Test
    void aDestinationItemWithNoPriceTakesTheSourcePriceAndAPricedOneKeepsItsOwn() {
        sourceItem.setUnitPrice(new BigDecimal("900"));
        when(items.findByPharmacyIdAndMedicationCatalogItemId(destination.getId(), sourceItem.getMedicationCatalogItem().getId()))
            .thenReturn(Optional.of(destinationItem));
        when(lots.findSameLot(destinationItem.getId(), sourceLot.getLotNumber(), sourceLot.getExpiryDate())).thenReturn(List.of());
        when(lots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(items.incrementOnHand(eq(destinationItem.getId()), eq(BigDecimal.TEN), any())).thenReturn(1);

        booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing);
        assertThat(destinationItem.getUnitPrice()).isEqualByComparingTo("900");

        destinationItem.setUnitPrice(new BigDecimal("375"));
        booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing);
        assertThat(destinationItem.getUnitPrice()).as("a store's own price is never overwritten").isEqualByComparingTo("375");
    }

    @Test
    void anItemDocumentedAsFreeStaysFreeAtItsDestination() {
        theSyrupMovesToAStoreThatNeverHeldIt();
        sourceItem.setFreeOfChargeReason("Programme national");

        booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing);

        ArgumentCaptor<StockTransaction> ledger = ArgumentCaptor.forClass(StockTransaction.class);
        verify(transactions).save(ledger.capture());
        InventoryItem created = ledger.getValue().getInventoryItem();
        assertThat(created.getFreeOfChargeReason()).isEqualTo("Programme national");
        assertThat(created.getUnitPrice()).as("a free item takes no price").isNull();
        // Review of #1380 (F4): the copied free-of-charge reason is kept in the history and audited.
        verify(priceHistory).keep(eq(created), isNull(), isNull(),
            eq(StockTransferBooking.TRANSFER_FREE_REASON_PREFIX + "Programme national"),
            eq(outgoing.getPerformedByUser().getId()), any());
        verify(auditEventLogService).logEvent(argThat(event ->
            event.getEventType() == com.example.hms.enums.AuditEventType.PHARMACY_PRICE_CHANGED
                && created.getId().toString().equals(event.getResourceId())));
    }

    @Test
    void aPartnerPharmacyIsNotADestination() {
        destination.setPharmacyType(com.example.hms.enums.PharmacyType.PARTNER_PHARMACY);
        // Lenient: the stores are locked in id order, so the refusal may come before the source is read.
        lenient().when(pharmacies.findLockedByIdAndHospitalId(source.getId(), source.getHospital().getId())).thenReturn(Optional.of(source));
        lenient().when(pharmacies.findLockedByIdAndHospitalId(destination.getId(), source.getHospital().getId())).thenReturn(Optional.of(destination));
        StockTransactionRequestDTO request = StockTransactionRequestDTO.builder()
            .transactionType(StockTransactionType.TRANSFER).referenceId(destination.getId()).build();

        assertThatThrownBy(() -> booking.prepare(request, sourceItem))
            .extracting("messageKey").isEqualTo(PharmacyStores.NOT_OWN_STORE_KEY);
        verifyNoInteractions(items, lots, transactions);
    }

    @Test
    void mergingALotKeepsItsHistoryButRecordsTheIncomingSupplier() {
        when(items.findByPharmacyIdAndMedicationCatalogItemId(destination.getId(), sourceItem.getMedicationCatalogItem().getId()))
            .thenReturn(Optional.of(destinationItem));
        StockLot existing = StockLot.builder().inventoryItem(destinationItem).lotNumber(sourceLot.getLotNumber())
            .expiryDate(sourceLot.getExpiryDate()).unitCost(sourceLot.getUnitCost()).supplier("Earlier supplier").build();
        existing.setId(UUID.randomUUID());
        when(lots.findSameLot(destinationItem.getId(), sourceLot.getLotNumber(), sourceLot.getExpiryDate())).thenReturn(List.of(existing));
        when(lots.tryLockById(existing.getId())).thenReturn(Optional.of(existing));
        when(lots.addReceipt(eq(existing.getId()), eq(BigDecimal.TEN), any())).thenReturn(1);
        when(items.incrementOnHand(eq(destinationItem.getId()), eq(BigDecimal.TEN), any())).thenReturn(1);
        booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing);
        ArgumentCaptor<StockTransaction> receipt = ArgumentCaptor.forClass(StockTransaction.class);
        verify(transactions).save(receipt.capture());
        assertThat(receipt.getValue().getSupplier()).isEqualTo(sourceLot.getSupplier());
        assertThat(existing.getSupplier()).isEqualTo("Earlier supplier");
    }

    @Test
    void aGenericReceiptReferenceCannotRedirectOrReplaceTheOwnedCounterpart() {
        when(transactions.findById(outgoing.getId())).thenReturn(Optional.of(outgoing));
        when(transactions.findByTransferOriginId(outgoing.getId())).thenReturn(Optional.empty());
        var request = StockTransactionRequestDTO.builder().inventoryItemId(sourceItem.getId())
            .transactionType(StockTransactionType.RETURN).referenceId(outgoing.getId()).quantity(BigDecimal.ONE).build();
        assertThatThrownBy(() -> booking.prepare(request, sourceItem))
            .extracting("messageKey").isEqualTo("stockTransaction.transfer.destinationUnavailable");
        verify(transactions, never()).findByReferenceIdAndTransactionType(any(), any());
        verifyNoInteractions(pharmacies);
    }

    @Test
    void anOwnedCounterpartWithTheWrongDestinationIsRefused() {
        var wrong = StockTransaction.builder().inventoryItem(sourceItem).transactionType(StockTransactionType.RECEIPT)
            .transferOriginId(outgoing.getId()).build();
        when(transactions.findById(outgoing.getId())).thenReturn(Optional.of(outgoing));
        when(transactions.findByTransferOriginId(outgoing.getId())).thenReturn(Optional.of(wrong));
        var request = StockTransactionRequestDTO.builder().transactionType(StockTransactionType.RETURN)
            .referenceId(outgoing.getId()).quantity(BigDecimal.ONE).build();
        assertThatThrownBy(() -> booking.prepare(request, sourceItem))
            .extracting("messageKey").isEqualTo("stockTransaction.transfer.destinationUnavailable");
        verifyNoInteractions(pharmacies);
    }

    @Test
    void aMissingDestinationLotUpdateCannotCreditTheItemOrWriteAReceipt() {
        when(items.findByPharmacyIdAndMedicationCatalogItemId(destination.getId(), sourceItem.getMedicationCatalogItem().getId()))
            .thenReturn(Optional.of(destinationItem));
        StockLot existing = StockLot.builder().inventoryItem(destinationItem).lotNumber(sourceLot.getLotNumber())
            .expiryDate(sourceLot.getExpiryDate()).unitCost(sourceLot.getUnitCost()).build();
        existing.setId(UUID.randomUUID());
        when(lots.findSameLot(destinationItem.getId(), sourceLot.getLotNumber(), sourceLot.getExpiryDate()))
            .thenReturn(List.of(existing));
        when(lots.tryLockById(existing.getId())).thenReturn(Optional.of(existing));
        when(lots.addReceipt(eq(existing.getId()), eq(BigDecimal.TEN), any())).thenReturn(0);

        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing))
            .extracting("messageKey").isEqualTo("stockTransaction.transfer.destinationUnavailable");
        verify(items, never()).incrementOnHand(any(), any(), any());
        verify(lots).addReceipt(eq(existing.getId()), eq(BigDecimal.TEN), any());
        verifyNoInteractions(transactions);
    }

    @Test
    void missingSameOrForeignDestinationIsRefusedBeforeStockChanges() {
        StockTransactionRequestDTO request = StockTransactionRequestDTO.builder().transactionType(StockTransactionType.TRANSFER).build();
        assertThatThrownBy(() -> booking.prepare(request, sourceItem)).extracting("messageKey").isEqualTo("stockTransaction.transfer.destinationRequired");
        request.setReferenceId(source.getId());
        assertThatThrownBy(() -> booking.prepare(request, sourceItem)).extracting("messageKey").isEqualTo("stockTransaction.transfer.destinationRequired");
        request.setReferenceId(destination.getId());
        when(pharmacies.findLockedByIdAndHospitalId(any(), eq(source.getHospital().getId()))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> booking.prepare(request, sourceItem)).extracting("messageKey").isEqualTo("stockTransaction.transfer.destinationUnavailable");
        verifyNoInteractions(items, lots, transactions);
    }

    @Test
    void sourceAndDestinationAreLockedInUuidOrder() {
        when(pharmacies.findLockedByIdAndHospitalId(source.getId(), source.getHospital().getId())).thenReturn(Optional.of(source));
        when(pharmacies.findLockedByIdAndHospitalId(destination.getId(), source.getHospital().getId())).thenReturn(Optional.of(destination));
        StockTransactionRequestDTO request = StockTransactionRequestDTO.builder()
            .transactionType(StockTransactionType.TRANSFER).referenceId(destination.getId()).build();
        assertThat(booking.prepare(request, sourceItem).destination()).isSameAs(destination);
        InOrder order = inOrder(pharmacies);
        for (UUID id : List.of(source.getId(), destination.getId()).stream().sorted().toList()) {
            order.verify(pharmacies).findLockedByIdAndHospitalId(id, source.getHospital().getId());
        }
        verify(items).tryLockById(sourceItem.getId());
        verifyNoInteractions(lots, transactions);
    }

    @Test
    void aTransferReturnRemovesTheSameQuantityFromTheReceivingStore() {
        StockLot destinationLot = StockLot.builder().inventoryItem(destinationItem).build();
        destinationLot.setId(UUID.randomUUID());
        when(lots.tryLockById(destinationLot.getId())).thenReturn(Optional.of(destinationLot));
        StockTransaction receipt = StockTransaction.builder().inventoryItem(destinationItem).stockLot(destinationLot)
            .transactionType(StockTransactionType.RECEIPT).referenceId(outgoing.getId()).quantity(BigDecimal.TEN).build();
        StockTransaction returned = StockTransaction.builder().inventoryItem(sourceItem).stockLot(sourceLot)
            .transactionType(StockTransactionType.RETURN).referenceId(outgoing.getId()).quantity(new BigDecimal("3"))
            .reason("Return unused ward stock").performedByUser(new User()).build();
        returned.setId(UUID.randomUUID());
        when(lots.decrementRemaining(eq(destinationLot.getId()), eq(returned.getQuantity()), any())).thenReturn(1);
        when(items.decrementOnHand(eq(destinationItem.getId()), eq(returned.getQuantity()), any())).thenReturn(1);
        booking.bookCounterpart(new StockTransferBooking.Route(destination, receipt), returned);
        ArgumentCaptor<StockTransaction> ledger = ArgumentCaptor.forClass(StockTransaction.class);
        verify(transactions).save(ledger.capture());
        assertThat(ledger.getValue().getQuantity()).isEqualByComparingTo("-3");
        assertThat(ledger.getValue().getTransactionType()).isEqualTo(StockTransactionType.ADJUSTMENT);
        assertThat(ledger.getValue().getReferenceId()).isEqualTo(returned.getId());
        assertThat(ledger.getValue().getInventoryItem()).isSameAs(destinationItem);
    }

    @Test
    void contentionRefusesTheTransferBeforeAnyStockChangesInsteadOfWaitingWithPartialLocks() {
        when(pharmacies.findLockedByIdAndHospitalId(source.getId(), source.getHospital().getId())).thenReturn(Optional.of(source));
        when(pharmacies.findLockedByIdAndHospitalId(destination.getId(), source.getHospital().getId())).thenReturn(Optional.of(destination));
        when(items.tryLockById(sourceItem.getId())).thenThrow(new org.springframework.dao.CannotAcquireLockException("Synthetic contention"));
        var request = StockTransactionRequestDTO.builder().transactionType(StockTransactionType.TRANSFER)
            .referenceId(destination.getId()).build();
        assertThatThrownBy(() -> booking.prepare(request, sourceItem))
            .extracting("messageKey").isEqualTo("stockTransaction.transfer.stockBusy");
        verifyNoInteractions(lots, transactions);
        verify(items, never()).decrementOnHand(any(), any(), any());
    }

    @Test
    void aFirstDestinationLotCannotAbsorbAnExistingLotlessBalance() {
        destinationItem.setQuantityOnHand(new BigDecimal("20"));
        when(items.findByPharmacyIdAndMedicationCatalogItemId(any(), any())).thenReturn(Optional.of(destinationItem));
        when(lots.sumRemainingForItem(destinationItem.getId())).thenReturn(BigDecimal.ZERO);
        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing))
            .extracting("messageKey").isEqualTo("pharmacy.inventory.lot.balanceUnreconciled");
        verify(lots, never()).saveAndFlush(any());
        verify(items, never()).incrementOnHand(any(), any(), any());
        verifyNoInteractions(transactions);
    }

    @Test
    void aLotlessReturnIsRefusedAfterTheReceiverStartsTrackingLots() {
        when(lots.existsByInventoryItemId(destinationItem.getId())).thenReturn(true);
        var receipt = StockTransaction.builder().inventoryItem(destinationItem).build();
        var returned = StockTransaction.builder().inventoryItem(sourceItem).transactionType(StockTransactionType.RETURN)
            .quantity(BigDecimal.ONE).build();
        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, receipt), returned))
            .extracting("messageKey").isEqualTo("pharmacy.inventory.lot.trackingChanged");
        verify(items, never()).decrementOnHand(any(), any(), any());
        verifyNoInteractions(transactions);
    }

    @Test
    void countedDestinationUnitsCannotReceiveAFraction() {
        outgoing.setQuantity(new BigDecimal("1.5"));
        when(items.findByPharmacyIdAndMedicationCatalogItemId(any(), any())).thenReturn(Optional.of(destinationItem));
        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing))
            .extracting("messageKey").isEqualTo(WholeUnitQuantity.DEFAULT_KEY);
        verify(items, never()).incrementOnHand(any(), any(), any());
        verifyNoInteractions(lots, transactions);
    }

    @Test
    void aTransferReturnAsksTheReceivingLotsFefoRuleBeforeDebitingIt() {
        StockLot destinationLot = StockLot.builder().inventoryItem(destinationItem).build();
        destinationLot.setId(UUID.randomUUID());
        when(lots.tryLockById(destinationLot.getId())).thenReturn(Optional.of(destinationLot));
        var receipt = StockTransaction.builder().inventoryItem(destinationItem).stockLot(destinationLot).build();
        var returned = StockTransaction.builder().inventoryItem(sourceItem).transactionType(StockTransactionType.RETURN)
            .quantity(BigDecimal.ONE).build();
        when(fefoLotPolicy.requireFefoOrReason(destinationLot, null))
            .thenThrow(new com.example.hms.exception.FieldBusinessException("fefoOverrideReason", "pharmacy.fefo.reasonRequired"));
        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, receipt), returned))
            .extracting("messageKey").isEqualTo("pharmacy.fefo.reasonRequired");
        verify(lots, never()).decrementRemaining(any(), any(), any());
        verify(items, never()).decrementOnHand(any(), any(), any());
        verifyNoInteractions(transactions);
    }

    @Test
    void aTransferCannotCreditALotExpiringToday() {
        sourceLot.setExpiryDate(LocalDate.now());
        when(items.findByPharmacyIdAndMedicationCatalogItemId(any(), any())).thenReturn(Optional.of(destinationItem));
        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing))
            .extracting("messageKey").isEqualTo("stockTransaction.transfer.expiredLot");
        verifyNoInteractions(lots, transactions);
    }

    @Test
    void aReturnRefusesChangedDestinationUnitsBeforeEitherQuantityIsChanged() {
        destinationItem.setUnit("capsule");
        var receipt = StockTransaction.builder().inventoryItem(destinationItem).build();
        var returned = StockTransaction.builder().inventoryItem(sourceItem).transactionType(StockTransactionType.RETURN)
            .quantity(BigDecimal.ONE).build();
        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, receipt), returned))
            .extracting("messageKey").isEqualTo("stockTransaction.transfer.unitsDiffer");
        verify(items, never()).decrementOnHand(any(), any(), any());
        verifyNoInteractions(lots, transactions);
    }

    @Test
    void differentStockUnitsNeverSilentlyConvertTheQuantity() {
        destinationItem.setUnit("box");
        when(items.findByPharmacyIdAndMedicationCatalogItemId(any(), any())).thenReturn(Optional.of(destinationItem));
        assertThatThrownBy(() -> booking.bookCounterpart(new StockTransferBooking.Route(destination, null), outgoing))
            .extracting("messageKey").isEqualTo("stockTransaction.transfer.unitsDiffer");
        verifyNoInteractions(lots, transactions);
        verify(items, never()).incrementOnHand(any(), any(), any());
    }
}
