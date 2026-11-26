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
        mock(MessageSource.class), roleValidator, actingScopeResolver,
        mock(com.example.hms.service.AuditEventLogService.class));

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
        when(assignmentRepository.findAllByUser_IdAndHospital_IdAndActiveTrue(callerId, HOSPITAL_ID))
            .thenReturn(java.util.List.of(callerAssignment));
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

    /** QA D656: POST /invoice-items on SENT invoice ae8e18f2 returned 201 and the total became 2000. */
    @Test
    void anIssuedInvoiceTakesNoNewLines_D656() {
        invoice.setStatus(InvoiceStatus.SENT);

        var request = line();
        assertThatThrownBy(() -> service.createInvoiceItem(request, Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("billing.invoice.issuedLinesLocked");
        verify(itemRepository, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(billingInvoiceService);
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

    /** QA D1088: a checkout line posted at zero is priced in place and keeps its owner and its service. */
    @Test
    void aZeroLinePricedInPlaceKeepsItsOwnerAndService_D1088() {
        UserRoleHospitalAssignment clinician = new UserRoleHospitalAssignment();
        clinician.setId(UUID.randomUUID());
        clinician.setHospital(invoice.getHospital());
        com.example.hms.model.Treatment chest = new com.example.hms.model.Treatment();
        chest.setId(UUID.randomUUID());
        InvoiceItem zero = InvoiceItem.builder().itemDescription("Radiographie thoracique").quantity(1)
            .unitPrice(BigDecimal.ZERO).totalPrice(BigDecimal.ZERO).itemCategory(ItemCategory.SERVICE)
            .assignment(clinician).relatedService(chest).build();
        zero.setId(UUID.randomUUID());
        zero.setBillingInvoice(invoice);
        when(itemRepository.findById(zero.getId())).thenReturn(Optional.of(zero));
        InvoiceItemRequestDTO priced = InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId())
            .itemDescription("Radiographie thoracique").quantity(1).unitPrice(new BigDecimal("7500")).overrideReason("Radio du jour")
            .itemCategory(ItemCategory.SERVICE).build();

        var saved = service.updateInvoiceItem(zero.getId(), priced, Locale.FRENCH);

        assertThat(saved.getId()).isEqualTo(zero.getId());
        assertThat(zero.getUnitPrice()).isEqualByComparingTo("7500");
        assertThat(zero.getAssignment()).isSameAs(clinician);
        assertThat(zero.getRelatedService()).isSameAs(chest);
        verify(billingInvoiceService).recomputeAndPersistTotals(invoice.getId());
    }

    /**
     * QA D1901: pricing a checkout line in an English session echoes its
     * English words; the line keeps its code and French copy. A description
     * the cashier retyped is theirs, shown as typed, with no code.
     */
    @Test
    void pricingAServerWordedLineKeepsItsCodeAndRetypingItDropsIt_D1901() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            InvoiceItem stay = InvoiceItem.builder().itemDescription("Hospitalisation (journées)")
                .textCode("billing.checkout.bedDays").quantity(2)
                .unitPrice(BigDecimal.ZERO).totalPrice(BigDecimal.ZERO).itemCategory(ItemCategory.SERVICE)
                .assignment(callerAssignment).build();
            stay.setId(UUID.randomUUID());
            stay.setBillingInvoice(invoice);
            when(itemRepository.findById(stay.getId())).thenReturn(Optional.of(stay));

            service.updateInvoiceItem(stay.getId(), InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId())
                .itemDescription("Hospital stay (days)").quantity(2).unitPrice(new BigDecimal("5000")).overrideReason("Tarif du service")
                .itemCategory(ItemCategory.SERVICE).build(), Locale.ENGLISH);
            assertThat(stay.getTextCode()).isEqualTo("billing.checkout.bedDays");
            assertThat(stay.getItemDescription()).isEqualTo("Hospitalisation (journées)");

            service.updateInvoiceItem(stay.getId(), InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId())
                .itemDescription("Chambre individuelle").quantity(2).unitPrice(new BigDecimal("5000")).overrideReason("Tarif du service")
                .itemCategory(ItemCategory.SERVICE).build(), Locale.ENGLISH);
            assertThat(stay.getTextCode()).isNull();
            assertThat(stay.getItemDescription()).isEqualTo("Chambre individuelle");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
    /**
     * QA D3603 / review of #1301 (L2): the category a plan rule sees survives pricing the line at the till (its
     * label and category echoed), and is dropped when the line is rewritten as something else - another label, or
     * another item category.
     */
    @Test
    void thePostedCoverageCategoryFollowsAPriceEditButNotARewrite_D3603() {
        InvoiceItem xray = InvoiceItem.builder().itemDescription("Radiographie du poignet gauche").quantity(1)
            .unitPrice(BigDecimal.ZERO).totalPrice(BigDecimal.ZERO).itemCategory(ItemCategory.SERVICE)
            .coverageCategory(com.example.hms.enums.PriceListCategory.IMAGING).assignment(callerAssignment).build();
        xray.setId(UUID.randomUUID());
        xray.setBillingInvoice(invoice);
        when(itemRepository.findById(xray.getId())).thenReturn(Optional.of(xray));

        service.updateInvoiceItem(xray.getId(), InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId())
            .itemDescription("Radiographie du poignet gauche").quantity(1).unitPrice(new BigDecimal("7500"))
            .overrideReason("Tarif du service").itemCategory(ItemCategory.SERVICE).build(), Locale.FRENCH);
        assertThat(xray.getCoverageCategory()).isEqualTo(com.example.hms.enums.PriceListCategory.IMAGING);

        service.updateInvoiceItem(xray.getId(), InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId())
            .itemDescription("Pansement simple").quantity(1).unitPrice(new BigDecimal("7500"))
            .itemCategory(ItemCategory.SERVICE).build(), Locale.FRENCH);
        assertThat(xray.getCoverageCategory()).isNull();

        InvoiceItem lab = InvoiceItem.builder().itemDescription("Glycémie").quantity(1)
            .unitPrice(new BigDecimal("1500")).totalPrice(new BigDecimal("1500")).itemCategory(ItemCategory.LAB_TEST)
            .coverageCategory(com.example.hms.enums.PriceListCategory.LAB_TEST).assignment(callerAssignment).build();
        lab.setId(UUID.randomUUID());
        lab.setBillingInvoice(invoice);
        when(itemRepository.findById(lab.getId())).thenReturn(Optional.of(lab));
        service.updateInvoiceItem(lab.getId(), InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId())
            .itemDescription("Glycémie").quantity(1).unitPrice(new BigDecimal("1500"))
            .itemCategory(ItemCategory.SERVICE).build(), Locale.FRENCH);
        assertThat(lab.getCoverageCategory()).isNull();
    }

    /* ── QA D3980 (r77): a line typed at the till bills the act picked from the price list ── */

    private com.example.hms.model.PriceListItem widal() {
        com.example.hms.model.PriceListItem item = com.example.hms.model.PriceListItem.builder()
            .hospitalId(HOSPITAL_ID).code("LAB-WIDAL").label("Sérodiagnostic de Widal")
            .category(com.example.hms.enums.PriceListCategory.LAB_TEST).build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private final com.example.hms.repository.PriceListItemRepository priceItems =
        mock(com.example.hms.repository.PriceListItemRepository.class);
    private final com.example.hms.service.billing.TariffResolver tariffs =
        mock(com.example.hms.service.billing.TariffResolver.class);

    private InvoiceItemRequestDTO typed(UUID itemId, String price) {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        service.setPriceList(priceItems, tariffs);
        invoice.setInvoiceDate(java.time.LocalDate.of(2026, 11, 25));
        return InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId()).itemDescription("widal")
            .quantity(1).unitPrice(new BigDecimal(price)).itemCategory(ItemCategory.GENERAL)
            .priceListItemId(itemId).build();
    }

    @Test
    void aTypedLineWithAPickedActIsATariffLineOfThatAct_D3980() {
        var widal = widal();
        UUID priceId = UUID.randomUUID();
        when(priceItems.findActiveByIdForCharge(widal.getId(), HOSPITAL_ID)).thenReturn(Optional.of(widal));
        when(tariffs.resolveItem(HOSPITAL_ID, widal.getId(), java.time.LocalDate.of(2026, 11, 25))).thenReturn(
            Optional.of(new com.example.hms.service.billing.TariffResolver.Tariff(widal.getId(), priceId,
                widal.getLabel(), new BigDecimal("3500"))));
        org.mockito.ArgumentCaptor<InvoiceItem> saved = org.mockito.ArgumentCaptor.forClass(InvoiceItem.class);

        service.createInvoiceItem(typed(widal.getId(), "3500"), Locale.FRENCH);

        verify(itemRepository).save(saved.capture());
        InvoiceItem line = saved.getValue();
        assertThat(line.getPriceListItemId()).isEqualTo(widal.getId());
        assertThat(line.getPriceListPriceId()).isEqualTo(priceId);
        assertThat(line.getCoverageCategory()).isEqualTo(com.example.hms.enums.PriceListCategory.LAB_TEST);
        assertThat(line.getItemCategory()).isEqualTo(ItemCategory.LAB_TEST);
        assertThat(line.getItemDescription()).isEqualTo("Sérodiagnostic de Widal");
        assertThat(line.getPriceSource()).isEqualTo(com.example.hms.enums.InvoiceLinePriceSource.TARIFF);
        assertThat(line.getOverrideReason()).isNull();
    }

    @Test
    void aPickedActAtAnotherPriceIsAnOverride_D3980() {
        var widal = widal();
        when(priceItems.findActiveByIdForCharge(widal.getId(), HOSPITAL_ID)).thenReturn(Optional.of(widal));
        when(tariffs.resolveItem(any(), any(), any())).thenReturn(Optional.of(
            new com.example.hms.service.billing.TariffResolver.Tariff(widal.getId(), UUID.randomUUID(),
                widal.getLabel(), new BigDecimal("3500"))));

        InvoiceItemRequestDTO noReason = typed(widal.getId(), "3000");
        assertThatThrownBy(() -> service.createInvoiceItem(noReason, Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "billing.item.overrideReasonRequired");

        InvoiceItemRequestDTO withReason = typed(widal.getId(), "3000");
        withReason.setOverrideReason("Tarif convenu");
        when(roleValidator.canOverrideTariff(callerId, HOSPITAL_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.createInvoiceItem(withReason, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(itemRepository, never()).save(any(InvoiceItem.class));

        when(roleValidator.canOverrideTariff(callerId, HOSPITAL_ID)).thenReturn(true);
        org.mockito.ArgumentCaptor<InvoiceItem> saved = org.mockito.ArgumentCaptor.forClass(InvoiceItem.class);
        service.createInvoiceItem(withReason, Locale.FRENCH);
        verify(itemRepository).save(saved.capture());
        assertThat(saved.getValue().getPriceSource()).isEqualTo(com.example.hms.enums.InvoiceLinePriceSource.MANUAL);
        assertThat(saved.getValue().getTariffUnitPrice()).isEqualByComparingTo("3500");
        assertThat(saved.getValue().getOverrideReason()).isEqualTo("Tarif convenu");
        assertThat(saved.getValue().getPriceListItemId()).isEqualTo(widal.getId());
    }

    @Test
    void anActOfAnotherHospitalOrWithdrawnIsRefused_D3980() {
        UUID elsewhere = UUID.randomUUID();
        when(priceItems.findActiveByIdForCharge(elsewhere, HOSPITAL_ID)).thenReturn(Optional.empty());

        InvoiceItemRequestDTO request = typed(elsewhere, "3500");
        assertThatThrownBy(() -> service.createInvoiceItem(request, Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "billing.item.priceListItemUnknown");
        verify(itemRepository, never()).save(any(InvoiceItem.class));
    }

    @Test
    void aTypedLineWithNoActBillsNoCategory_D3980() {
        org.mockito.ArgumentCaptor<InvoiceItem> saved = org.mockito.ArgumentCaptor.forClass(InvoiceItem.class);
        InvoiceItemRequestDTO typedLab = line();
        typedLab.setItemCategory(ItemCategory.LAB_TEST);

        service.createInvoiceItem(typedLab, Locale.FRENCH);

        verify(itemRepository).save(saved.capture());
        assertThat(saved.getValue().getPriceListItemId()).isNull();
        assertThat(saved.getValue().getCoverageCategory()).as("never claimed from the insurer").isNull();
        assertThat(saved.getValue().getPriceSource()).isEqualTo(com.example.hms.enums.InvoiceLinePriceSource.MANUAL);
    }
}
