package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.ConflictException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G15 rule 8: the one owner of cancelling a prepared fill.
 */
@ExtendWith(MockitoExtension.class)
class PreparedFillVoiderTest {

    @Mock private DispenseRepository dispenseRepository;
    @Mock private StockLotRepository stockLotRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private PharmacyServiceSupport support;
    @Mock private EntityManager entityManager;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T09:00:00Z"), ZoneOffset.UTC);

    private PreparedFillVoider voider;
    private Prescription prescription;
    private Dispense prepared;
    private StockLot lot;
    private InventoryItem item;
    private Patient patient;
    private Pharmacy pharmacy;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        voider = new PreparedFillVoider(dispenseRepository, stockLotRepository, inventoryItemRepository,
                stockTransactionRepository, support, entityManager, CLOCK);

        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        pharmacy = Pharmacy.builder().name("Main").build();
        pharmacy.setId(UUID.randomUUID());
        item = InventoryItem.builder().quantityOnHand(BigDecimal.valueOf(90)).build();
        lot = StockLot.builder().inventoryItem(item).remainingQuantity(BigDecimal.valueOf(40)).build();
        prepared = Dispense.builder()
                .prescription(prescription)
                .patient(patient)
                .pharmacy(pharmacy)
                .stockLot(lot)
                .medicationName("Amoxicillin")
                .quantityRequested(BigDecimal.TEN)
                .quantityDispensed(BigDecimal.TEN)
                .status(DispenseStatus.PENDING)
                .build();
        prepared.setId(UUID.randomUUID());
    }

    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private void theUpdateCancels(ReadyCancelReason reason) {
        when(dispenseRepository.cancelPreparedFill(eq(prepared.getId()), eq(reason), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    prepared.setStatus(DispenseStatus.CANCELLED);
                    prepared.setCancelReason(reason);
                    return 1;
                });
        when(entityManager.contains(prepared)).thenReturn(true);
        when(support.resolveCurrentUser()).thenReturn(new User());
    }

    @ParameterizedTest
    @EnumSource(ReadyCancelReason.class)
    @DisplayName("AC-7/AC-8: one conditional cancel, the row refreshed, the stock RETURNED (STOCK_UNAVAILABLE too), audit and SMS")
    void cancelReturnsTheStock(ReadyCancelReason reason) {
        theUpdateCancels(reason);

        Dispense result = voider.cancel(prepared, reason);

        assertThat(result.getStatus()).isEqualTo(DispenseStatus.CANCELLED);
        verify(entityManager).refresh(prepared);
        assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("50");
        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("100");
        ArgumentCaptor<StockTransaction> tx = ArgumentCaptor.forClass(StockTransaction.class);
        verify(stockTransactionRepository).save(tx.capture());
        assertThat(tx.getValue().getTransactionType()).isEqualTo(StockTransactionType.RETURN);
        assertThat(tx.getValue().getQuantity()).isEqualByComparingTo("10");
        verify(support).logAudit(eq(AuditEventType.DISPENSE_READY_CANCELLED),
                argThat(d -> d.contains(reason.name()) && !d.contains("Amoxicillin")),
                eq(prepared.getId().toString()), eq("DISPENSE"));
        verify(support).notifyReadyCancelled(patient, pharmacy, "Amoxicillin");
    }

    @Test
    @DisplayName("AC-7: a row that is no longer PENDING is a 409, and nothing is returned or sent")
    void notPendingIsAConflict() {
        when(dispenseRepository.cancelPreparedFill(any(), any(), any())).thenReturn(0);
        when(entityManager.contains(prepared)).thenReturn(false);
        when(dispenseRepository.findById(prepared.getId())).thenReturn(Optional.of(prepared));

        assertThatThrownBy(() -> voider.cancel(prepared, ReadyCancelReason.OTHER))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This fill is no longer waiting for collection.");
        verifyNoInteractions(stockLotRepository, inventoryItemRepository, stockTransactionRepository);
        verify(support, never()).notifyReadyCancelled(any(), any(), any());
        verify(support, never()).logAudit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a fill prepared without a lot has no stock to return")
    void noLotNoReturn() {
        prepared.setStockLot(null);
        when(dispenseRepository.cancelPreparedFill(any(), any(), any())).thenReturn(1);
        when(entityManager.contains(prepared)).thenReturn(true);

        voider.cancel(prepared, ReadyCancelReason.NOT_COLLECTED);

        verifyNoInteractions(stockLotRepository, inventoryItemRepository, stockTransactionRepository);
        verify(support).notifyReadyCancelled(patient, pharmacy, "Amoxicillin");
    }

    @Test
    @DisplayName("AC-8: the prescriber's withdrawal voids the open preparation with its reason")
    void withdrawalVoidsTheOpenPreparation() {
        when(dispenseRepository.findFirstByPrescription_IdAndStatus(prescription.getId(), DispenseStatus.PENDING))
                .thenReturn(Optional.of(prepared));
        theUpdateCancels(ReadyCancelReason.PRESCRIPTION_WITHDRAWN);

        voider.voidPreparedFill(prescription, ReadyCancelReason.PRESCRIPTION_WITHDRAWN);

        assertThat(prepared.getCancelReason()).isEqualTo(ReadyCancelReason.PRESCRIPTION_WITHDRAWN);
        verify(support).notifyReadyCancelled(patient, pharmacy, "Amoxicillin");
    }

    @Test
    @DisplayName("no open preparation: nothing to void")
    void nothingToVoid() {
        when(dispenseRepository.findFirstByPrescription_IdAndStatus(prescription.getId(), DispenseStatus.PENDING))
                .thenReturn(Optional.empty());

        voider.voidPreparedFill(prescription, ReadyCancelReason.PRESCRIPTION_CHANGED);

        verify(dispenseRepository, never()).cancelPreparedFill(any(), any(), any());
        verifyNoInteractions(support);
    }

    @Test
    @DisplayName("AC-8: no hospital-scope dependency, so a global-view super-admin's withdrawal still voids")
    void makesNoScopeCall() {
        java.util.List<Class<?>> fieldTypes = Arrays.stream(PreparedFillVoider.class.getDeclaredFields())
                .<Class<?>>map(java.lang.reflect.Field::getType)
                .toList();
        assertThat(fieldTypes).isNotEmpty().doesNotContain(RoleValidator.class);
    }
}
