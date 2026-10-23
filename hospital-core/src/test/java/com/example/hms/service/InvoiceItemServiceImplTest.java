package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.InvoiceItemMapper;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D72: lines could not be added, and the line endpoints read the invoice
 * by id with no hospital check (create, update and delete), trusting a
 * caller-supplied assignment.
 */
class InvoiceItemServiceImplTest {

    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    private final InvoiceItemRepository itemRepository = mock(InvoiceItemRepository.class);
    private final BillingInvoiceRepository invoiceRepository = mock(BillingInvoiceRepository.class);
    private final UserRoleHospitalAssignmentRepository assignmentRepository =
        mock(UserRoleHospitalAssignmentRepository.class);
    private final TreatmentRepository treatmentRepository = mock(TreatmentRepository.class);
    private final BillingInvoiceService billingInvoiceService = mock(BillingInvoiceService.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final ActingScopeResolver actingScopeResolver = mock(ActingScopeResolver.class);

    private final InvoiceItemServiceImpl service = new InvoiceItemServiceImpl(itemRepository, invoiceRepository,
        assignmentRepository, treatmentRepository, new InvoiceItemMapper(), billingInvoiceService,
        mock(MessageSource.class), roleValidator, actingScopeResolver);

    private BillingInvoice invoice;
    private UserRoleHospitalAssignment callerAssignment;
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        invoice = BillingInvoice.builder().hospital(hospital).status(InvoiceStatus.DRAFT)
            .totalAmount(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).build();
        invoice.setId(UUID.randomUUID());
        callerAssignment = new UserRoleHospitalAssignment();
        callerAssignment.setId(UUID.randomUUID());
        callerAssignment.setHospital(hospital);
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(actingScopeResolver.requirePinned()).thenReturn(HOSPITAL_ID);
        when(roleValidator.getCurrentUserId()).thenReturn(callerId);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(callerId, HOSPITAL_ID))
            .thenReturn(Optional.of(callerAssignment));
        when(itemRepository.save(any(InvoiceItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private InvoiceItemRequestDTO line() {
        return InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId()).itemDescription("Consultation")
            .quantity(2).unitPrice(new BigDecimal("2500")).itemCategory(ItemCategory.GENERAL).build();
    }

    @Test
    void aLineWithoutAnAssignmentIsAttributedToTheCallerAndRecomputesTheTotal() {
        var saved = service.createInvoiceItem(line(), Locale.ENGLISH);

        assertThat(saved.getAssignmentId()).isEqualTo(callerAssignment.getId());
        verify(billingInvoiceService).recomputeAndPersistTotals(invoice.getId());
    }

    /** QA D633: a line whose total overflows numeric(12,2) answered in English ("numeric value out of bounds"). */
    @Test
    void aLineTotalOutOfRangeIsRefusedInTheCallersLanguage_D633() {
        InvoiceItemRequestDTO huge = line();
        huge.setUnitPrice(new BigDecimal("9999999999"));
        huge.setQuantity(5);
        assertThatThrownBy(() -> service.createInvoiceItem(huge, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "billing.item.totalOutOfRange");
        org.mockito.Mockito.verifyNoInteractions(billingInvoiceService);

        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateInvoiceItem(id, huge, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "billing.item.totalOutOfRange");
    }

    @Test
    void theLargestLineTotalStillFits_D633() {
        InvoiceItemRequestDTO max = line();
        max.setUnitPrice(new BigDecimal("4999999999"));
        max.setQuantity(2);
        service.createInvoiceItem(max, Locale.ENGLISH);
        verify(billingInvoiceService).recomputeAndPersistTotals(invoice.getId());
    }

    @Test
    void aDecimalXofPriceIsRefusedButZeroCentsAreWhole_D307() {
        InvoiceItemRequestDTO decimal = line();
        decimal.setUnitPrice(new BigDecimal("1500.50"));
        assertThatThrownBy(() -> service.createInvoiceItem(decimal, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "billing.payment.wholeAmountOnly");
        verify(itemRepository, never()).save(any(InvoiceItem.class));

        InvoiceItemRequestDTO zeroCents = line();
        zeroCents.setUnitPrice(new BigDecimal("1500.00"));
        assertThat(service.createInvoiceItem(zeroCents, Locale.ENGLISH)).isNotNull();
    }

    @Test
    void aLineOnAnotherHospitalsInvoiceIsNotFound() {
        when(actingScopeResolver.requirePinned()).thenReturn(UUID.randomUUID());

        var request = line();
        assertThatThrownBy(() -> service.createInvoiceItem(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(itemRepository, never()).save(any());
    }

    @Test
    void aCancelledInvoiceTakesNoLines() {
        invoice.setStatus(InvoiceStatus.CANCELLED);

        var request = line();
        assertThatThrownBy(() -> service.createInvoiceItem(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aPaidInvoiceTakesNoNewLines_D147() {
        invoice.setStatus(InvoiceStatus.PAID);

        var request = line();
        assertThatThrownBy(() -> service.createInvoiceItem(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("billing.invoice.paidLinesLocked");
        verify(itemRepository, never()).save(any());
    }

    @Test
    void aLineOfAPaidInvoiceCannotBeDeleted_D147() {
        invoice.setStatus(InvoiceStatus.PAID);
        InvoiceItem item = InvoiceItem.builder().billingInvoice(invoice).build();
        item.setId(UUID.randomUUID());
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        UUID itemId = item.getId();
        assertThatThrownBy(() -> service.deleteInvoiceItem(itemId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
        verify(itemRepository, never()).delete(any());
    }

    @Test
    void deletingALineOfAnotherHospitalIsNotFound() {
        InvoiceItem item = InvoiceItem.builder().billingInvoice(invoice).build();
        item.setId(UUID.randomUUID());
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(actingScopeResolver.requirePinned()).thenReturn(UUID.randomUUID());

        UUID itemId = item.getId();
        assertThatThrownBy(() -> service.deleteInvoiceItem(itemId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(itemRepository, never()).delete(any());
    }
}
