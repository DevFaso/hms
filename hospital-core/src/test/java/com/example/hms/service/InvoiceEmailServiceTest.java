package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.EmailInvoiceRequest;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** QA D755 / D758: an invoice is mailed only from its own hospital, and mailing never issues it. */
@ExtendWith(MockitoExtension.class)
class InvoiceEmailServiceTest {

    @Mock private BillingInvoiceRepository billingInvoiceRepository;
    @Mock private InvoiceItemRepository invoiceItemRepository;
    @Mock private PdfInvoiceService pdfInvoiceService;
    @Mock private EmailService emailService;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.repository.InvoiceAdjustmentRepository adjustmentRepository;
    @InjectMocks private InvoiceEmailService service;

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();

    private final EmailInvoiceRequest request =
        new EmailInvoiceRequest(List.of("billing@example.test"), null, null, "<b>hi</b>", "fr", false);

    private BillingInvoice invoiceAtA(InvoiceStatus status) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(UUID.randomUUID());
        invoice.setInvoiceNumber("INV-1");
        invoice.setStatus(status);
        Hospital h = new Hospital();
        h.setId(hospitalA);
        invoice.setHospital(h);
        when(billingInvoiceRepository.findByIdWithRefs(invoice.getId())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    @DisplayName("QA D755: billing staff of hospital B cannot mail hospital A's invoice (404, nothing sent)")
    void otherHospitalsInvoiceIsNotFound() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        UUID id = invoice.getId();

        assertThatThrownBy(() -> service.emailInvoice(id, request))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(emailService, pdfInvoiceService);
    }

    @Test
    @DisplayName("QA D755: a random id is a 404, not a 500")
    void unknownIdIsNotFound() {
        UUID id = UUID.randomUUID();
        when(billingInvoiceRepository.findByIdWithRefs(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.emailInvoice(id, request)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("QA D755: the invoice's own hospital still mails it, the custom message escaped")
    void ownHospitalMailsIt() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of());

        service.emailInvoice(invoice.getId(), request);

        verify(emailService).sendWithAttachment(any(), any(), any(), anyString(),
            contains("&lt;b&gt;hi&lt;/b&gt;"), any(), anyString(), anyString());
        assertEquals(InvoiceStatus.SENT, invoice.getStatus());
    }

    @Test
    @DisplayName("QA D758: a DRAFT is refused and stays DRAFT")
    void draftIsRefusedAndKept() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.DRAFT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        UUID id = invoice.getId();

        assertThatThrownBy(() -> service.emailInvoice(id, request))
            .isInstanceOf(BusinessException.class);
        assertEquals(InvoiceStatus.DRAFT, invoice.getStatus());
        verify(billingInvoiceRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void emailingAPaidInvoice_keepsItPaid() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.PAID);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of());

        service.emailInvoice(invoice.getId(), request);

        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        verify(billingInvoiceRepository, never()).save(any());
    }

    @Test
    void theMailedPdfCarriesTheAdjustmentsWithoutTheReason_D974() {
        BillingInvoice invoice = invoiceAtA(InvoiceStatus.SENT);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(invoiceItemRepository.findByBillingInvoiceId(invoice.getId())).thenReturn(List.of());
        when(adjustmentRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId())).thenReturn(List.of(
            com.example.hms.model.InvoiceAdjustment.builder().invoice(invoice)
                .type(com.example.hms.enums.InvoiceAdjustmentType.WAIVER)
                .amount(new java.math.BigDecimal("500")).reason("indigent").build()));
        when(pdfInvoiceService.generateInvoicePdf(any(), any(), any(), any(), any())).thenReturn(new byte[] {1});

        service.emailInvoice(invoice.getId(),
            new EmailInvoiceRequest(List.of("billing@example.test"), null, null, null, "fr", true));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.example.hms.model.InvoiceAdjustment>> printed =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(pdfInvoiceService).generateInvoicePdf(any(), any(), any(), printed.capture(), any());
        assertEquals(1, printed.getValue().size());
        assertEquals(com.example.hms.enums.InvoiceAdjustmentType.WAIVER, printed.getValue().get(0).getType());
        org.junit.jupiter.api.Assertions.assertNull(printed.getValue().get(0).getReason());
    }
}
