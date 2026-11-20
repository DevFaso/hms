package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InvoiceLinePriceSource;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.InvoiceItemMapper;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.payload.dto.InvoiceItemResponseDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

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
 * Billing-money plan PR 3 (owner decision D-2): a cashier may change the price of a DRAFT line priced from the price
 * list only with a reason. The line keeps the tariff's price beside the new one, becomes MANUAL, and the override is
 * audited (ids only). Only a billing specialist or a hospital admin of the hospital may do it; an issued invoice's
 * lines stay locked.
 */
@DisplayName("Invoice line: overriding a price-list price")
class TariffOverrideTest {

    private static final UUID HOSPITAL_ID = UUID.randomUUID();

    private final InvoiceItemRepository itemRepository = mock(InvoiceItemRepository.class);
    private final BillingInvoiceRepository invoiceRepository = mock(BillingInvoiceRepository.class);
    private final UserRoleHospitalAssignmentRepository assignmentRepository =
        mock(UserRoleHospitalAssignmentRepository.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final ActingScopeResolver actingScopeResolver = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final InvoiceItemServiceImpl service = new InvoiceItemServiceImpl(itemRepository, invoiceRepository,
        assignmentRepository, mock(TreatmentRepository.class), new InvoiceItemMapper(),
        mock(BillingInvoiceService.class), mock(MessageSource.class), roleValidator, actingScopeResolver, audit);

    private final UUID cashier = UUID.randomUUID();
    private BillingInvoice invoice;
    private InvoiceItem line;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_ID);
        invoice = BillingInvoice.builder().hospital(hospital).status(InvoiceStatus.DRAFT)
            .totalAmount(BigDecimal.ZERO).amountPaid(BigDecimal.ZERO).build();
        invoice.setId(UUID.randomUUID());
        UserRoleHospitalAssignment owner = new UserRoleHospitalAssignment();
        owner.setId(UUID.randomUUID());
        owner.setHospital(hospital);
        line = InvoiceItem.builder().billingInvoice(invoice).itemDescription("Consultation générale").quantity(1)
            .itemCategory(ItemCategory.SERVICE).unitPrice(new BigDecimal("2000.00")).assignment(owner)
            .priceSource(InvoiceLinePriceSource.TARIFF).priceListItemId(UUID.randomUUID())
            .priceListPriceId(UUID.randomUUID()).build();
        line.setId(UUID.randomUUID());
        when(itemRepository.findById(line.getId())).thenReturn(Optional.of(line));
        when(itemRepository.save(any(InvoiceItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(actingScopeResolver.requirePinned()).thenReturn(HOSPITAL_ID);
        when(roleValidator.getCurrentUserId()).thenReturn(cashier);
        when(roleValidator.canOverrideTariff(cashier, HOSPITAL_ID)).thenReturn(true);
    }

    private InvoiceItemRequestDTO priced(String price, String reason) {
        return InvoiceItemRequestDTO.builder().billingInvoiceId(invoice.getId())
            .itemDescription("Consultation générale").quantity(1).itemCategory(ItemCategory.SERVICE)
            .unitPrice(new BigDecimal(price)).overrideReason(reason).build();
    }

    @Test
    @DisplayName("without a reason the change is refused, naming the reason field (Motif)")
    void aReasonIsRequired() {
        assertThatThrownBy(() -> service.updateInvoiceItem(line.getId(), priced("1500", "  "), Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("overrideReason");
                assertThat(e.getMessageKey()).isEqualTo("billing.item.overrideReasonRequired");
            });
        verify(itemRepository, never()).save(any());
        assertThat(line.getUnitPrice()).isEqualByComparingTo("2000");
        assertThat(line.getPriceSource()).isEqualTo(InvoiceLinePriceSource.TARIFF);
    }

    @Test
    @DisplayName("with a reason: the new price, the tariff kept, the reason, MANUAL, and one audit row with ids only")
    void anOverrideKeepsTheTariffAndIsAudited() {
        InvoiceItemResponseDTO saved = service.updateInvoiceItem(line.getId(),
            priced("1500", "Patient indigent, accord du major"), Locale.FRENCH);

        assertThat(saved.getUnitPrice()).isEqualByComparingTo("1500");
        assertThat(saved.getTariffUnitPrice()).isEqualByComparingTo("2000");
        assertThat(saved.getOverrideReason()).isEqualTo("Patient indigent, accord du major");
        assertThat(saved.getPriceSource()).isEqualTo("MANUAL");
        assertThat(line.getPriceListPriceId()).as("the version the tariff came from stays named").isNotNull();
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.INVOICE_LINE_PRICE_OVERRIDDEN);
        assertThat(event.getValue().getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(event.getValue().getResourceId()).isEqualTo(line.getId().toString());
        assertThat(event.getValue().getEventDescription())
            .doesNotContain("indigent").doesNotContain("1500").doesNotContain("2000");
    }

    @Test
    @DisplayName("set back to the tariff's price, the line is a TARIFF line again, with no reason")
    void backToTheTariff() {
        service.updateInvoiceItem(line.getId(), priced("1500", "Remise"), Locale.FRENCH);
        InvoiceItemResponseDTO back = service.updateInvoiceItem(line.getId(), priced("2000", null), Locale.FRENCH);

        assertThat(back.getPriceSource()).isEqualTo("TARIFF");
        assertThat(back.getTariffUnitPrice()).isEqualByComparingTo("2000");
        assertThat(back.getOverrideReason()).isNull();
        assertThat(line.getTariffUnitPrice()).isNull();
    }

    @Test
    @DisplayName("the quantity of an overridden line changes without asking the reason again")
    void theQuantityChangesFreely() {
        service.updateInvoiceItem(line.getId(), priced("1500", "Remise"), Locale.FRENCH);
        InvoiceItemRequestDTO twice = priced("1500", null);
        twice.setQuantity(2);

        InvoiceItemResponseDTO saved = service.updateInvoiceItem(line.getId(), twice, Locale.FRENCH);

        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getOverrideReason()).isEqualTo("Remise");
        verify(audit, org.mockito.Mockito.times(1)).logEvent(any());
    }

    @Test
    @DisplayName("QA D2703: an unpriced line priced by hand needs a reason, keeps it, and is audited (ids only)")
    void anUnpricedLineNeedsAReasonAndIsAudited() {
        line.setPriceSource(InvoiceLinePriceSource.UNPRICED);
        line.setPriceListItemId(null);
        line.setPriceListPriceId(null);
        line.setUnitPrice(new BigDecimal("0.00"));
        UUID lineId = line.getId();
        InvoiceItemRequestDTO noReason = priced("25000", null);

        assertThatThrownBy(() -> service.updateInvoiceItem(lineId, noReason, Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("overrideReason"));
        verify(itemRepository, never()).save(any());
        assertThat(line.getUnitPrice()).isEqualByComparingTo("0");

        InvoiceItemResponseDTO saved = service.updateInvoiceItem(lineId,
            priced("25000", "Prix ACC-NORMAL de la grille, version absente le jour de la naissance"), Locale.FRENCH);

        assertThat(saved.getPriceSource()).isEqualTo("MANUAL");
        assertThat(saved.getTariffUnitPrice()).isNull();
        assertThat(saved.getOverrideReason()).startsWith("Prix ACC-NORMAL");
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.INVOICE_LINE_PRICED_MANUALLY);
        assertThat(event.getValue().getEventDescription()).doesNotContain("25000").doesNotContain("ACC-NORMAL");
    }

    @Test
    @DisplayName("QA D2703: a hand-priced line set to 0 F CFA needs a reason too; another hand price does not")
    void aHandPricedLineSetToZeroNeedsAReason() {
        line.setPriceSource(InvoiceLinePriceSource.MANUAL);
        line.setPriceListItemId(null);
        line.setPriceListPriceId(null);
        line.setUnitPrice(new BigDecimal("1000.00"));
        UUID lineId = line.getId();

        service.updateInvoiceItem(lineId, priced("1200", null), Locale.FRENCH);
        verify(audit, never()).logEvent(any());
        InvoiceItemRequestDTO free = priced("0", null);
        assertThatThrownBy(() -> service.updateInvoiceItem(lineId, free, Locale.FRENCH))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("D-1/D-2: someone who is neither the cashier nor a hospital admin here (the super-admin alone) is refused")
    void onlyTheCashierOrAnAdminMayOverride() {
        when(roleValidator.canOverrideTariff(cashier, HOSPITAL_ID)).thenReturn(false);

        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.FRENCH);
        try {
            assertThatThrownBy(() -> service.updateInvoiceItem(line.getId(), priced("1500", "Remise"), Locale.FRENCH))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("modifier un prix de la grille");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        verify(itemRepository, never()).save(any());
        verify(audit, never()).logEvent(any());
    }

    @Test
    @DisplayName("on an issued invoice the lines stay locked (existing rule)")
    void anIssuedInvoiceStaysLocked() {
        invoice.setStatus(InvoiceStatus.SENT);

        assertThatThrownBy(() -> service.updateInvoiceItem(line.getId(), priced("1500", "Remise"), Locale.FRENCH))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("billing.invoice.issuedLinesLocked");
        verify(audit, never()).logEvent(any());
    }

    @Test
    @DisplayName("a line typed at the till is MANUAL")
    void aTypedLineIsManual() {
        when(assignmentRepository.findAllByUser_IdAndHospital_IdAndActiveTrue(cashier, HOSPITAL_ID))
            .thenReturn(java.util.List.of(line.getAssignment()));
        InvoiceItemResponseDTO typed = service.createInvoiceItem(priced("500", null), Locale.FRENCH);
        assertThat(typed.getPriceSource()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("#1197 L2: setting an overridden line back to the price list is audited, ids only")
    void aRestoreIsAudited() {
        service.updateInvoiceItem(line.getId(), priced("1500", "Remise"), Locale.FRENCH);
        service.updateInvoiceItem(line.getId(), priced("2000", null), Locale.FRENCH);

        ArgumentCaptor<AuditEventRequestDTO> events = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, org.mockito.Mockito.times(2)).logEvent(events.capture());
        assertThat(events.getAllValues()).extracting(AuditEventRequestDTO::getEventType).containsExactly(
            AuditEventType.INVOICE_LINE_PRICE_OVERRIDDEN, AuditEventType.INVOICE_LINE_PRICE_RESTORED);
        assertThat(events.getAllValues().get(1).getEventDescription()).doesNotContain("Remise").doesNotContain("2000");
    }

    @Test
    @DisplayName("#1197 M2: a price-list line is not deleted without a reason (it could be re-added lower unseen)")
    void deletingATariffLineNeedsAReason() {
        assertThatThrownBy(() -> service.deleteInvoiceItem(line.getId(), " ", Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("reason");
                assertThat(e.getMessageKey()).isEqualTo("billing.item.deleteReasonRequired");
            });
        assertThatThrownBy(() -> service.deleteInvoiceItem(line.getId(), Locale.FRENCH))
            .isInstanceOf(FieldBusinessException.class);
        verify(itemRepository, never()).delete(any());
        verify(audit, never()).logEvent(any());
    }

    @Test
    @DisplayName("#1197 M2 / QA D2101: only the cashier or an admin of the hospital deletes a price-list line, "
        + "and the refusal names the removal, not a price change")
    void deletingATariffLineNeedsTheRole() {
        when(roleValidator.canOverrideTariff(cashier, HOSPITAL_ID)).thenReturn(false);

        for (Locale locale : java.util.List.of(Locale.FRENCH, Locale.ENGLISH, Locale.forLanguageTag("es"))) {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
            try {
                String removal = MessageUtil.resolve(locale, "billing.item.deleteNotAllowed");
                String priceChange = MessageUtil.resolve(locale, "billing.item.overrideNotAllowed");
                assertThat(removal).isNotEqualTo(priceChange).doesNotContain("[Missing translation]");
                java.util.UUID lineId = line.getId();
                assertThatThrownBy(() -> service.deleteInvoiceItem(lineId, "Doublon", locale))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage(removal);
            } finally {
                org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            }
        }
        assertThat(MessageUtil.resolve(Locale.FRENCH, "billing.item.deleteNotAllowed")).contains("retirer");
        verify(itemRepository, never()).delete(any());
    }

    @Test
    @DisplayName("#1197 M2: with a reason the line goes, audited with ids in the text and the reason in the details")
    void aTariffLineDeletionIsAudited() {
        service.deleteInvoiceItem(line.getId(), "Doublon de la consultation", Locale.FRENCH);

        verify(itemRepository).delete(line);
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.INVOICE_LINE_DELETED);
        assertThat(event.getValue().getEventDescription()).contains(line.getId().toString())
            .contains(line.getPriceListPriceId().toString()).doesNotContain("Doublon");
        assertThat(event.getValue().getDetails()).isEqualTo(java.util.Map.of("reason", "Doublon de la consultation"));
    }

    private void aLineWithNoPriceListPrice(String price) {
        line.setPriceSource(InvoiceLinePriceSource.MANUAL);
        line.setPriceListItemId(null);
        line.setPriceListPriceId(null);
        line.setUnitPrice(new BigDecimal(price));
    }

    @Test
    @DisplayName("PR 10 review (QA D2703): a line with no price-list price is not deleted without a reason either")
    void deletingAnyLineNeedsAReason() {
        aLineWithNoPriceListPrice("0");

        assertThatThrownBy(() -> service.deleteInvoiceItem(line.getId(), null, Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("reason");
                assertThat(e.getMessageKey()).isEqualTo("billing.item.removeReasonRequired");
            });
        assertThatThrownBy(() -> service.deleteInvoiceItem(line.getId(), "4111 1111 1111 1111", Locale.FRENCH))
            .isInstanceOf(FieldBusinessException.class);
        verify(itemRepository, never()).delete(any());
        verify(audit, never()).logEvent(any());
    }

    @Test
    @DisplayName("PR 10 review (QA D2703): the removal is kept for the day report (line, price, who, why) and audited")
    void aRemovalIsKeptAndAudited() {
        com.example.hms.repository.InvoiceLineRemovalRepository removals =
            mock(com.example.hms.repository.InvoiceLineRemovalRepository.class);
        service.setLineRemovalRepository(removals);
        invoice.setInvoiceNumber("TWP-2026-000040");
        aLineWithNoPriceListPrice("0");
        line.setItemDescription("Radiographie thorax");

        service.deleteInvoiceItem(line.getId(), " Acte non réalisé ", Locale.FRENCH);

        verify(itemRepository).delete(line);
        ArgumentCaptor<com.example.hms.model.InvoiceLineRemoval> kept =
            ArgumentCaptor.forClass(com.example.hms.model.InvoiceLineRemoval.class);
        verify(removals).save(kept.capture());
        assertThat(kept.getValue().getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(kept.getValue().getInvoiceId()).isEqualTo(invoice.getId());
        assertThat(kept.getValue().getInvoiceNumber()).isEqualTo("TWP-2026-000040");
        assertThat(kept.getValue().getItemDescription()).isEqualTo("Radiographie thorax");
        assertThat(kept.getValue().getUnitPrice()).isEqualByComparingTo("0");
        assertThat(kept.getValue().getReason()).isEqualTo("Acte non réalisé");
        assertThat(kept.getValue().getRemovedBy()).isEqualTo(cashier);
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.INVOICE_LINE_DELETED);
        assertThat(event.getValue().getEventDescription()).doesNotContain("réalisé");
        assertThat(event.getValue().getDetails()).isEqualTo(java.util.Map.of("reason", "Acte non réalisé"));
    }

    @Test
    @DisplayName("PR 10 review (QA D2703): a line typed on a visit's invoice needs a reason, kept and audited")
    void aLineTypedOnAVisitInvoiceNeedsAReason() {
        when(assignmentRepository.findAllByUser_IdAndHospital_IdAndActiveTrue(cashier, HOSPITAL_ID))
            .thenReturn(java.util.List.of(line.getAssignment()));
        invoice.setEncounter(new com.example.hms.model.Encounter());

        assertThatThrownBy(() -> service.createInvoiceItem(priced("500", "  "), Locale.FRENCH))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("overrideReason");
                assertThat(e.getMessageKey()).isEqualTo("billing.item.addedLineReasonRequired");
            });
        verify(itemRepository, never()).save(any());

        InvoiceItemResponseDTO typed = service.createInvoiceItem(priced("500", "Radio refaite, tarif convenu"),
            Locale.FRENCH);

        assertThat(typed.getPriceSource()).isEqualTo("MANUAL");
        assertThat(typed.getOverrideReason()).isEqualTo("Radio refaite, tarif convenu");
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(AuditEventType.INVOICE_LINE_PRICED_MANUALLY);
        assertThat(event.getValue().getEventDescription()).doesNotContain("Radio").doesNotContain("500");
        assertThat(event.getValue().getDetails())
            .isEqualTo(java.util.Map.of("reason", "Radio refaite, tarif convenu"));
    }

    @Test
    @DisplayName("PR 10 review: an invoice made by hand with no visit takes its lines with no reason, and no audit row")
    void aLineOnAHandMadeInvoiceNeedsNoReason() {
        when(assignmentRepository.findAllByUser_IdAndHospital_IdAndActiveTrue(cashier, HOSPITAL_ID))
            .thenReturn(java.util.List.of(line.getAssignment()));

        InvoiceItemResponseDTO typed = service.createInvoiceItem(priced("500", null), Locale.FRENCH);

        assertThat(typed.getOverrideReason()).isNull();
        verify(audit, never()).logEvent(any());
    }
}
