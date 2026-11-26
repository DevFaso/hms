package com.example.hms.service.billing;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.payload.dto.billing.InvoiceLinePreAuthorisationDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Insurer claims plan PR 1 (V383, QA D3984): the desk or billing records the pre-authorisation number of one invoice
 * line at its hospital; the line's imaging order takes it too; roles, tenancy and the number's rules.
 */
@DisplayName("Pre-authorisation number on an invoice line")
class InvoiceLinePreAuthorisationServiceTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID DESK = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-26T09:00:00Z"), ZoneOffset.UTC);

    private final BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
    private final InvoiceItemRepository items = mock(InvoiceItemRepository.class);
    private final ImagingOrderService imaging = mock(ImagingOrderService.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private InvoiceLinePreAuthorisationService service;
    private BillingInvoice invoice;
    private InvoiceItem line;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        service = new InvoiceLinePreAuthorisationService(invoices, items,
            new InvoiceLinePreAuthorisations(items, audit, CLOCK), imaging, roles, scope, audit, CLOCK);
        when(scope.requirePinned()).thenReturn(HOSPITAL);
        when(roles.getCurrentUserId()).thenReturn(DESK);
        when(roles.canRecordPreAuthorisation(DESK, HOSPITAL)).thenReturn(true);
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL);
        invoice = BillingInvoice.builder().hospital(hospital).status(InvoiceStatus.SENT).build();
        invoice.setId(UUID.randomUUID());
        line = InvoiceItem.builder().itemDescription("Radio thorax").build();
        line.setId(UUID.randomUUID());
        when(invoices.findLockedById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(items.findByIdAndBillingInvoice_IdAndBillingInvoice_Hospital_Id(line.getId(), invoice.getId(), HOSPITAL))
            .thenReturn(Optional.of(line));
    }

    @Test
    @DisplayName("the number is written on the line with who and when, trimmed; the amounts are not touched")
    void recordsTheNumberOnTheLine() {
        InvoiceLinePreAuthorisationDTO answer = service.recordOnLine(invoice.getId(), line.getId(), "  BON  2026/117 ");

        assertThat(answer.getPreAuthorisationNumber()).isEqualTo("BON 2026/117");
        assertThat(answer.isOrderUpdated()).isFalse();
        verify(items).recordPreAuthorisation(List.of(line.getId()), HOSPITAL, "BON 2026/117", DESK,
            LocalDateTime.now(CLOCK));
        verify(imaging, never()).recordAuthorizationFromInvoiceLine(any(), any(), anyString());
    }

    @Test
    @DisplayName("a line that bills an imaging order gives the order its number too")
    void theImagingOrderTakesTheNumber() {
        UUID order = UUID.randomUUID();
        line.setSourceOrderType(BillableOrderType.IMAGING_ORDER.name());
        line.setSourceOrderId(order);
        when(imaging.recordAuthorizationFromInvoiceLine(order, HOSPITAL, "BON-117")).thenReturn(true);

        assertThat(service.recordOnLine(invoice.getId(), line.getId(), "BON-117").isOrderUpdated()).isTrue();
    }

    @Test
    @DisplayName("a role that records no number, another hospital's invoice, a cancelled one: refused")
    void refusals() {
        UUID invoiceId = invoice.getId();
        UUID lineId = line.getId();
        when(roles.canRecordPreAuthorisation(DESK, HOSPITAL)).thenReturn(false);
        assertThatThrownBy(() -> service.recordOnLine(invoiceId, lineId, "BON-1"))
            .isInstanceOf(ClientSafeAccessDeniedException.class);

        when(roles.canRecordPreAuthorisation(DESK, HOSPITAL)).thenReturn(true);
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        invoice.setHospital(other);
        assertThatThrownBy(() -> service.recordOnLine(invoiceId, lineId, "BON-1"))
            .isInstanceOf(ResourceNotFoundException.class);

        Hospital own = new Hospital();
        own.setId(HOSPITAL);
        invoice.setHospital(own);
        invoice.setStatus(InvoiceStatus.CANCELLED);
        assertThatThrownBy(() -> service.recordOnLine(invoiceId, lineId, "BON-1"))
            .isInstanceOf(ConflictException.class);
        verify(items, never()).recordPreAuthorisation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a line of another invoice or hospital answers as a missing one")
    void aForeignLineIsNotFound() {
        UUID invoiceId = invoice.getId();
        UUID foreign = UUID.randomUUID();
        when(items.findByIdAndBillingInvoice_IdAndBillingInvoice_Hospital_Id(eq(foreign), any(), any()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordOnLine(invoiceId, foreign, "BON-1"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("blank, too long or odd characters: refused on the number field")
    void theNumbersRules() {
        assertThatThrownBy(() -> InvoiceLinePreAuthorisations.normalise("   ", "number"))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> InvoiceLinePreAuthorisations.normalise("A".repeat(121), "number"))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> InvoiceLinePreAuthorisations.normalise("<script>", "number"))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(InvoiceLinePreAuthorisations.normalise("PEC-2026/0042#3", "number")).isEqualTo("PEC-2026/0042#3");
    }
}
