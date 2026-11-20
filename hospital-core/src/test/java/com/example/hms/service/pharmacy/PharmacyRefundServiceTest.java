package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.enums.PharmacySaleStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyRefund;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.model.pharmacy.SaleLine;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundReasonRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacyRefundRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.payment.PaymentReferenceRegistry;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D2676 (r65, billing plan PR 13): a patient return's refund follows the two-person rule. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PharmacyRefundService (QA D2676)")
class PharmacyRefundServiceTest {

    @Mock private PharmacyRefundRepository refundRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private PharmacySaleRepository saleRepository;
    @Mock private DispenseRepository dispenseRepository;
    @Mock private PharmacyPaymentRepository paymentRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private PaymentReferenceRegistry referenceRegistry;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;
    @Mock private AuditEventLogService auditEventLogService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private PharmacyRefundService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();
    private final UUID accountant = UUID.randomUUID();
    private Pharmacy pharmacy;
    private StockLot lot;
    private PharmacySale sale;
    private StockTransaction ret;

    @BeforeEach
    void setUp() {
        service = new PharmacyRefundService(refundRepository, stockTransactionRepository, saleRepository,
                dispenseRepository, paymentRepository, inventoryItemRepository, userRepository, referenceRegistry,
                roleValidator, support, auditEventLogService, clock);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        pharmacy = Pharmacy.builder().hospital(hospital).name("Pharmacie").build();
        pharmacy.setId(UUID.randomUUID());
        MedicationCatalogItem med = MedicationCatalogItem.builder().nameFr("Paracétamol").hospital(hospital).build();
        med.setId(UUID.randomUUID());
        InventoryItem item = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(med)
                .unitPrice(new BigDecimal("250")).build();
        item.setId(UUID.randomUUID());
        lot = StockLot.builder().inventoryItem(item).lotNumber("LOT-A-R65-1").build();
        lot.setId(UUID.randomUUID());

        // A counter sale of 10 at 250 = 2 500 F CFA, paid cash.
        sale = PharmacySale.builder().hospital(hospital).pharmacy(pharmacy)
                .paymentMethod(PharmacyPaymentMethod.CASH).status(PharmacySaleStatus.COMPLETED)
                .totalAmount(new BigDecimal("2500")).build();
        sale.setId(UUID.randomUUID());
        sale.addLine(SaleLine.builder().medicationCatalogItem(med).stockLot(lot).quantity(BigDecimal.TEN)
                .unitPrice(new BigDecimal("250")).lineTotal(new BigDecimal("2500")).build());

        // 4 of them came back.
        ret = StockTransaction.builder().inventoryItem(item).stockLot(lot).transactionType(StockTransactionType.RETURN)
                .quantity(new BigDecimal("4")).referenceId(sale.getId()).build();
        ret.setId(UUID.randomUUID());

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(stockTransactionRepository.findById(ret.getId())).thenReturn(Optional.of(ret));
        when(saleRepository.findLockedById(sale.getId())).thenReturn(Optional.of(sale));
        when(refundRepository.sumForSale(eq(sale.getId()), any())).thenReturn(BigDecimal.ZERO);
        when(refundRepository.save(any(PharmacyRefund.class))).thenAnswer(inv -> {
            PharmacyRefund r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
    }

    private void actAs(UUID userId) {
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        boolean approver = userId.equals(accountant);
        when(roleValidator.canRequestPharmacyRefund(userId, hospitalId)).thenReturn(true);
        when(roleValidator.canApproveRefund(userId, hospitalId)).thenReturn(approver);
        when(roleValidator.canPayPharmacyRefund(userId, hospitalId)).thenReturn(!approver);
    }

    private PharmacyRefundRequestDTO body() {
        return PharmacyRefundRequestDTO.builder().returnTransactionId(ret.getId()).reason("Erreur de produit").build();
    }

    private PharmacyRefund stored(PharmacyRefundStatus status, UUID requestedBy) {
        PharmacyRefund r = PharmacyRefund.builder().hospitalId(hospitalId).pharmacyId(pharmacy.getId())
                .returnTransactionId(ret.getId()).saleId(sale.getId()).quantity(new BigDecimal("4"))
                .amount(new BigDecimal("1000")).method(PharmacyPaymentMethod.CASH).reason("Erreur")
                .status(status).requestedBy(requestedBy).build();
        r.setId(UUID.randomUUID());
        when(refundRepository.findLockedByIdAndHospitalId(r.getId(), hospitalId)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("a request is priced by the server: 4 returned at the 250 paid = 1 000 F CFA, REQUESTED, cash as paid")
    void requestIsPricedFromThePricePaid() {
        actAs(pharmacist);

        PharmacyRefundDTO dto = service.request(body());

        assertThat(dto.getAmount()).isEqualByComparingTo("1000");
        assertThat(dto.getStatus()).isEqualTo("REQUESTED");
        assertThat(dto.getMethod()).isEqualTo("CASH");
        assertThat(dto.getSaleId()).isEqualTo(sale.getId());
    }

    @Test
    @DisplayName("a refund never gives back more than was paid less the sale's other live refunds")
    void requestIsBoundedByWhatWasPaid() {
        actAs(pharmacist);
        when(refundRepository.sumForSale(eq(sale.getId()), any())).thenReturn(new BigDecimal("2000"));

        assertThat(service.request(body()).getAmount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("a second refund of the same return is refused")
    void oneLiveRefundPerReturn() {
        actAs(pharmacist);
        when(refundRepository.existsByReturnTransactionIdAndStatusIn(eq(ret.getId()), any())).thenReturn(true);

        assertThatThrownBy(() -> service.request(body())).isInstanceOf(ConflictException.class);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("a return of another hospital answers as missing")
    void anotherHospitalsReturnIsMissing() {
        actAs(pharmacist);
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(roleValidator.canRequestPharmacyRefund(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.request(body())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a request without a reason is refused on reason")
    void reasonIsRequired() {
        actAs(pharmacist);
        PharmacyRefundRequestDTO request = body();
        request.setReason(" ");

        assertThatThrownBy(() -> service.request(request))
                .isInstanceOf(FieldBusinessException.class).extracting("field").isEqualTo("reason");
    }

    @Test
    @DisplayName("a role that may not ask for a refund is refused before any lookup")
    void requesterRoleIsChecked() {
        when(roleValidator.getCurrentUserId()).thenReturn(pharmacist);
        when(roleValidator.canRequestPharmacyRefund(pharmacist, hospitalId)).thenReturn(false);

        assertThatThrownBy(() -> service.request(body())).isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(stockTransactionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("two-person rule: the person who asked cannot approve, even as an accountant")
    void noSelfApproval() {
        actAs(accountant);
        PharmacyRefund refund = stored(PharmacyRefundStatus.REQUESTED, accountant);

        assertThatThrownBy(() -> service.approve(refund.getId())).isInstanceOf(ConflictException.class);
        assertThat(refund.getStatus()).isEqualTo(PharmacyRefundStatus.REQUESTED);
    }

    @Test
    @DisplayName("two-person rule: a pharmacist cannot approve")
    void approverRoleIsChecked() {
        actAs(pharmacist);
        PharmacyRefund refund = stored(PharmacyRefundStatus.REQUESTED, UUID.randomUUID());

        assertThatThrownBy(() -> service.approve(refund.getId())).isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    @DisplayName("another accountant approves; the pharmacist pays it out; the fully refunded sale is REFUNDED")
    void approveThenPay() {
        actAs(accountant);
        PharmacyRefund refund = stored(PharmacyRefundStatus.REQUESTED, pharmacist);
        refund.setAmount(new BigDecimal("2500"));

        assertThat(service.approve(refund.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(refund.getDecidedBy()).isEqualTo(accountant);

        actAs(pharmacist);
        when(refundRepository.sumForSale(eq(sale.getId()), any())).thenReturn(new BigDecimal("2500"));
        PharmacyRefundDTO paid = service.pay(refund.getId(), null);

        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaidBy()).isEqualTo(pharmacist);
        assertThat(paid.getPaidAt()).isEqualTo(java.time.LocalDateTime.now(clock));
        assertThat(sale.getStatus()).isEqualTo(PharmacySaleStatus.REFUNDED);
    }

    @Test
    @DisplayName("a refund not approved is never paid")
    void onlyApprovedIsPaid() {
        actAs(pharmacist);
        PharmacyRefund refund = stored(PharmacyRefundStatus.REQUESTED, UUID.randomUUID());

        assertThatThrownBy(() -> service.pay(refund.getId(), null)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a rejection needs its reason and keeps it")
    void rejectKeepsTheReason() {
        actAs(accountant);
        PharmacyRefund refund = stored(PharmacyRefundStatus.REQUESTED, pharmacist);

        assertThatThrownBy(() -> service.reject(refund.getId(), new PharmacyRefundReasonRequestDTO(null)))
                .isInstanceOf(FieldBusinessException.class);
        service.reject(refund.getId(), new PharmacyRefundReasonRequestDTO("Produit entamé"));

        assertThat(refund.getStatus()).isEqualTo(PharmacyRefundStatus.REJECTED);
        assertThat(refund.getDecisionReason()).isEqualTo("Produit entamé");
    }

    @Test
    @DisplayName("a reason holding a card number is refused")
    void cardNumbersAreRefused() {
        actAs(pharmacist);
        PharmacyRefundRequestDTO request = body();
        request.setReason("carte 4111 1111 1111 1111");

        assertThatThrownBy(() -> service.request(request)).isInstanceOf(FieldBusinessException.class);
        ArgumentCaptor<PharmacyRefund> saved = ArgumentCaptor.forClass(PharmacyRefund.class);
        verify(refundRepository, never()).save(saved.capture());
    }

    @Test
    @DisplayName("a withdrawal needs its reason, only before payment; a paid refund cannot be cancelled")
    void cancelNeedsAReason() {
        actAs(pharmacist);
        PharmacyRefund refund = stored(PharmacyRefundStatus.REQUESTED, pharmacist);

        assertThatThrownBy(() -> service.cancel(refund.getId(), new PharmacyRefundReasonRequestDTO(" ")))
                .isInstanceOf(FieldBusinessException.class);
        service.cancel(refund.getId(), new PharmacyRefundReasonRequestDTO("Erreur de saisie"));
        assertThat(refund.getStatus()).isEqualTo(PharmacyRefundStatus.CANCELLED);
        assertThat(refund.getCancelReason()).isEqualTo("Erreur de saisie");

        PharmacyRefund paid = stored(PharmacyRefundStatus.PAID, pharmacist);
        assertThatThrownBy(() -> service.cancel(paid.getId(), new PharmacyRefundReasonRequestDTO("trop tard")))
                .isInstanceOf(ConflictException.class);
    }
}
