package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.StockBalanceReconciliationRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, com.example.hms.i18n.EnglishMessageBundles.class})
class StockBalanceReconciliationServiceTest {
    @Mock InventoryItemRepository items;
    @Mock StockLotRepository lots;
    @Mock StockTransactionRepository ledger;
    @Mock UserRepository users;
    @Mock RoleValidator roles;
    @Mock ControlledStockWitness witness;
    @Mock EntityManager entityManager;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Mock StockCountService access;
    @Mock com.example.hms.repository.pharmacy.PharmacyRepository pharmacies;
    StockBalanceReconciliationService service;
    UUID hospitalId = UUID.randomUUID(), itemId = UUID.randomUUID(), userId = UUID.randomUUID(), lotId = UUID.randomUUID();
    InventoryItem item;
    @BeforeEach void setup() {
        service = new StockBalanceReconciliationService(items, lots, ledger, users, roles, witness, entityManager, jdbc, access, pharmacies);
        var hospital = new Hospital(); hospital.setId(hospitalId);
        var pharmacy = Pharmacy.builder().hospital(hospital).build();
        item = InventoryItem.builder().pharmacy(pharmacy).unit("tablet").quantityOnHand(new BigDecimal("20")).build();
        item.setId(itemId); item.setStockRevision(1L);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roles.getCurrentUserId()).thenReturn(userId);
        when(roles.isSuperAdminActingAt(hospitalId)).thenReturn(true);
    }
    StockBalanceReconciliationRequest request(boolean confirmed) {
        return new StockBalanceReconciliationRequest(new BigDecimal("20"), new BigDecimal("15"), 1L, confirmed, "Counted all batches", null,
            java.util.List.of(new StockBalanceReconciliationRequest.LotCount(lotId, new BigDecimal("15"))));
    }
    void stock() {
        when(items.findById(itemId)).thenReturn(Optional.of(item));
        when(items.lockById(itemId)).thenReturn(Optional.of(item));
        var lot = com.example.hms.model.pharmacy.StockLot.builder().inventoryItem(item).remainingQuantity(new BigDecimal("15")).build(); lot.setId(lotId);
        when(lots.findAllByInventoryItemIdOrderByExpiryDateAsc(itemId)).thenReturn(java.util.List.of(lot));
        when(lots.tryLockById(lotId)).thenReturn(Optional.of(lot));
    }
    @Test void explicitReconciliationChangesOnlyTheAggregateAndKeepsAnAdjustmentLedger() {
        stock(); var actor = new User(); actor.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(actor));
        when(items.setOnHand(eq(itemId), eq(new BigDecimal("15")), any())).thenReturn(1);
        when(ledger.saveAndFlush(any())).thenAnswer(call -> { StockTransaction entry = call.getArgument(0); entry.setId(UUID.randomUUID()); return entry; });
        assertThat(service.reconcile(itemId, request(true))).isNotNull();
        var entry = org.mockito.ArgumentCaptor.forClass(StockTransaction.class);
        verify(ledger).saveAndFlush(entry.capture());
        assertThat(entry.getValue().getQuantity()).isEqualByComparingTo("-5");
        assertThat(entry.getValue().getStockLot()).isNull();
        assertThat(entry.getValue().getReason()).contains("Counted all batches");
        verify(jdbc).update(contains("INSERT INTO clinical.stock_balance_reconciliation_lot_counts"), eq(entry.getValue().getId()), eq(lotId), eq(new BigDecimal("15")));
        verify(lots).findAllByInventoryItemIdOrderByExpiryDateAsc(itemId);
        verify(lots).tryLockById(lotId);
        verifyNoMoreInteractions(lots);
    }
    @Test void withoutConfirmationNoStockOrLedgerIsTouched() {
        assertThatThrownBy(() -> service.reconcile(itemId, request(false))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(items, lots, ledger, witness);
    }
    @Test void changedItemOrLotTotalForcesTheUserToReload() {
        stock(); item.setStockRevision(2L);
        assertThatThrownBy(() -> service.reconcile(itemId, request(true))).hasFieldOrPropertyWithValue("messageKey", "stockCount.changedSinceCount");
        verifyNoInteractions(ledger, witness);
    }
    @Test void anotherHospitalsItemCannotBeReconciled() {
        when(items.findById(itemId)).thenReturn(Optional.of(item));
        item.getPharmacy().getHospital().setId(UUID.randomUUID());
        assertThatThrownBy(() -> service.reconcile(itemId, request(true)))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verifyNoInteractions(lots, ledger, witness);
        verify(items, never()).lockById(any());
    }
    @Test void witnessRefusalPreventsControlledStockAdjustment() {
        stock(); var actor = new User(); actor.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(actor));
        when(witness.resolve(null, item, actor)).thenThrow(new BusinessException("stockTransaction.witness.required"));
        assertThatThrownBy(() -> service.reconcile(itemId, request(true)))
            .hasFieldOrPropertyWithValue("messageKey", "stockTransaction.witness.required");
        verifyNoInteractions(ledger);
        verify(items, never()).setOnHand(any(), any(), any());
    }
    @Test void anUnverifiedPhysicalLotIsRefused() {
        stock();
        var unverified = new StockBalanceReconciliationRequest(new BigDecimal("20"), new BigDecimal("15"), 1L, true, "physical count", null,
            java.util.List.of(new StockBalanceReconciliationRequest.LotCount(lotId, new BigDecimal("14"))));
        assertThatThrownBy(() -> service.reconcile(itemId, unverified))
            .hasFieldOrPropertyWithValue("messageKey", "stockCount.lotCountEvidenceRequired");
        verifyNoInteractions(ledger, witness);
        verify(items, never()).setOnHand(any(), any(), any());
    }
}
