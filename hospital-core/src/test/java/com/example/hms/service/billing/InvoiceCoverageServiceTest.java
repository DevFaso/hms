package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.PayerShareStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.InvoicePayerShare;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.Payer;
import com.example.hms.model.PayerPlan;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.model.PayerPlanTerms;
import com.example.hms.model.PriceListItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.InvoicePayerShareDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceAdjustmentRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.InvoicePayerShareRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PayerPlanRepository;
import com.example.hms.repository.PayerPlanRuleRepository;
import com.example.hms.repository.PayerPlanTermsRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.InvoiceBalance;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 7: the coverage applied when an invoice is issued (BC2 with RAMU 80 %, lab excluded), the
 * plan and coverage dates, the payer's decisions (a rejection with its reason moves the share back to the patient),
 * the roles, tenancy, the cancel and the credit-note recomputation, over an in-memory share ledger whose conditional
 * UPDATE behaves like the real one.
 */
@DisplayName("Payer coverage applied to the invoice")
class InvoiceCoverageServiceTest {

    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();
    private static final UUID BILLING_A = UUID.randomUUID();
    private static final UUID DOCTOR_A = UUID.randomUUID();
    private static final LocalDate SERVICE_DAY = LocalDate.of(2026, 11, 17);

    private final InvoicePayerShareRepository shares = mock(InvoicePayerShareRepository.class);
    private final BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
    private final InvoiceItemRepository items = mock(InvoiceItemRepository.class);
    private final InvoiceAdjustmentRepository adjustments = mock(InvoiceAdjustmentRepository.class);
    private final PatientInsuranceRepository insurances = mock(PatientInsuranceRepository.class);
    private final PayerRepository payers = mock(PayerRepository.class);
    private final PayerPlanRepository plans = mock(PayerPlanRepository.class);
    private final PayerPlanTermsRepository terms = mock(PayerPlanTermsRepository.class);
    private final PayerPlanRuleRepository rules = mock(PayerPlanRuleRepository.class);
    private final PriceListItemRepository priceItems = mock(PriceListItemRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    private final Map<UUID, InvoicePayerShare> ledger = new HashMap<>();
    private final Map<UUID, BigDecimal> creditNotes = new HashMap<>();
    private InvoiceCoverageService service;
    private BillingInvoice invoice;
    private PatientInsurance coverage;
    private PayerPlan plan;
    private List<InvoiceItem> lines;
    private InvoiceItem consultation;
    private InvoiceItem tdr;
    private InvoiceItem xray;

    /** The French locale set for the refusals never leaks into a test run after this one. */
    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        service = new InvoiceCoverageService(shares, invoices, items, adjustments, insurances, payers, plans, terms,
            rules, priceItems, roles, scope, audit, clock);
        when(scope.requirePinned()).thenReturn(HOSPITAL_A);
        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL_A);
        when(roles.getCurrentUserId()).thenReturn(BILLING_A);
        when(roles.canDecidePayerShare(BILLING_A, HOSPITAL_A)).thenReturn(true);

        invoice = invoice(HOSPITAL_A);
        // RAMU, Formule Base 80 %, laboratoire exclu, plafond annuel 150 000 F.
        Payer ramu = Payer.builder().hospitalId(HOSPITAL_A).code("RAMU").name("RAMU").build();
        ramu.setId(UUID.randomUUID());
        plan = PayerPlan.builder().hospitalId(HOSPITAL_A).payerId(ramu.getId()).code("BASE").name("Formule Base")
            .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        plan.setId(UUID.randomUUID());
        PayerPlanTerms base = PayerPlanTerms.builder().hospitalId(HOSPITAL_A).planId(plan.getId())
            .defaultCoveragePercent(80).annualCeilingPerBeneficiary(new BigDecimal("150000"))
            .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        PayerPlanRule lab = PayerPlanRule.builder().hospitalId(HOSPITAL_A).planId(plan.getId())
            .category(PriceListCategory.LAB_TEST).excluded(true).effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        when(payers.findByIdAndHospitalId(ramu.getId(), HOSPITAL_A)).thenReturn(Optional.of(ramu));
        when(plans.findByIdAndHospitalId(plan.getId(), HOSPITAL_A)).thenReturn(Optional.of(plan));
        when(terms.findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(plan.getId(), HOSPITAL_A))
            .thenReturn(List.of(base));
        when(rules.findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(plan.getId(), HOSPITAL_A))
            .thenReturn(List.of(lab));

        coverage = PatientInsurance.builder().primary(true).providerName("RAMU").policyNumber("BC2-001")
            .payerPlanId(plan.getId()).effectiveDate(LocalDate.of(2026, 1, 1)).build();
        coverage.setId(UUID.randomUUID());
        when(insurances.findByPatient_IdAndAssignment_Hospital_Id(invoice.getPatient().getId(), HOSPITAL_A))
            .thenReturn(List.of(coverage));
        when(insurances.findByIdAndAssignment_Hospital_Id(coverage.getId(), HOSPITAL_A)).thenReturn(Optional.of(coverage));
        when(insurances.lockById(coverage.getId())).thenReturn(Optional.of(coverage));

        // Consultation 2 000 + TDR 1 500 (a lab test of the price list) + X-ray 5 000.
        PriceListItem tdrItem = PriceListItem.builder().hospitalId(HOSPITAL_A).category(PriceListCategory.LAB_TEST)
            .build();
        tdrItem.setId(UUID.randomUUID());
        when(priceItems.findAllById(any())).thenReturn(List.of(tdrItem));
        consultation = line("2000", ItemCategory.SERVICE, null, 1);
        tdr = line("1500", ItemCategory.GENERAL, tdrItem.getId(), 2);
        xray = line("5000", ItemCategory.SERVICE, null, 3);
        lines = new ArrayList<>(List.of(consultation, tdr, xray));
        when(items.findByBillingInvoiceId(invoice.getId())).thenAnswer(call -> lines);
        invoice.setTotalAmount(new BigDecimal("8500"));

        when(adjustments.sumCreditNotesByInvoiceItemId(any())).thenAnswer(call ->
            creditNotes.getOrDefault(call.<UUID>getArgument(0), BigDecimal.ZERO));
        when(shares.saveAndFlush(any(InvoicePayerShare.class))).thenAnswer(call -> {
            InvoicePayerShare share = call.getArgument(0);
            share.setId(UUID.randomUUID());
            share.setCreatedAt(LocalDateTime.now(clock));
            ledger.put(share.getId(), share);
            return share;
        });
        when(shares.findByInvoiceIdAndHospitalIdAndStatusIn(any(), any(), any())).thenAnswer(call ->
            ledger.values().stream()
                .filter(s -> s.getInvoiceId().equals(call.getArgument(0)) && s.getHospitalId().equals(call.getArgument(1)))
                .filter(s -> call.<Collection<PayerShareStatus>>getArgument(2).contains(s.getStatus()))
                .toList());
        when(shares.findByInvoiceIdAndHospitalIdOrderByCreatedAtAsc(any(), any())).thenAnswer(call ->
            ledger.values().stream()
                .filter(s -> s.getInvoiceId().equals(call.getArgument(0)) && s.getHospitalId().equals(call.getArgument(1)))
                .toList());
        when(shares.findByIdAndInvoiceIdAndHospitalId(any(), any(), any())).thenAnswer(call ->
            Optional.ofNullable(ledger.get(call.<UUID>getArgument(0)))
                .filter(s -> s.getInvoiceId().equals(call.getArgument(1)) && s.getHospitalId().equals(call.getArgument(2))));
        when(shares.sumUsedInYear(any(), any(), anyInt(), any())).thenReturn(BigDecimal.ZERO);
        when(shares.decide(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            InvoicePayerShare share = ledger.get(call.<UUID>getArgument(0));
            if (share == null || !share.getHospitalId().equals(call.getArgument(1))
                    || !call.<Collection<PayerShareStatus>>getArgument(2).contains(share.getStatus())) {
                return 0;
            }
            share.setStatus(call.getArgument(3));
            share.setDecidedBy(call.getArgument(4));
            share.setDecidedAt(call.getArgument(5));
            share.setRejectionReason(call.getArgument(6));
            return 1;
        });
    }

    private BillingInvoice invoice(UUID hospitalId) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        BillingInvoice built = BillingInvoice.builder().hospital(hospital).patient(patient)
            .invoiceNumber("CHWF-2026-000042").invoiceDate(SERVICE_DAY).totalAmount(BigDecimal.ZERO)
            .amountPaid(BigDecimal.ZERO).status(InvoiceStatus.SENT).build();
        built.setId(UUID.randomUUID());
        when(invoices.findLockedById(built.getId())).thenReturn(Optional.of(built));
        when(invoices.findById(built.getId())).thenReturn(Optional.of(built));
        return built;
    }

    private static InvoiceItem line(String total, ItemCategory category, UUID priceListItemId, int order) {
        InvoiceItem item = InvoiceItem.builder().itemDescription("line " + order).quantity(1).itemCategory(category)
            .unitPrice(new BigDecimal(total)).totalPrice(new BigDecimal(total)).priceListItemId(priceListItemId)
            .build();
        item.setId(UUID.randomUUID());
        item.setCreatedAt(LocalDateTime.of(2026, 11, 17, 9, order));
        return item;
    }

    private InvoicePayerShare onlyShare() {
        assertThat(ledger.values().stream().filter(s -> s.getStatus().isLive()).toList()).hasSize(1);
        return ledger.values().stream().filter(s -> s.getStatus().isLive()).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("QA D3258 / D3408 (r70): a quote is the invoice rule on one charge: payer, plan, policy and the split")
    void quoteIsTheInvoiceRuleOnOneCharge() {
        UUID patientId = invoice.getPatient().getId();
        LocalDate day = LocalDate.of(2026, 11, 22);

        InvoiceCoverageService.CoverageQuote quote = service.quote(patientId, HOSPITAL_A, day,
            new BigDecimal("2000"), PriceListCategory.CONSULTATION, null).orElseThrow();
        assertThat(quote.payerName()).isEqualTo("RAMU");
        assertThat(quote.planName()).isEqualTo("Formule Base");
        assertThat(quote.policyNumber()).isEqualTo("BC2-001");
        assertThat(quote.payerShare()).isEqualByComparingTo("1600");
        assertThat(quote.patientShare()).isEqualByComparingTo("400");
        // An excluded category: the payer pays nothing of it.
        assertThat(service.quote(patientId, HOSPITAL_A, day, new BigDecimal("1500"), PriceListCategory.LAB_TEST, null)
            .orElseThrow().payerShare()).isEqualByComparingTo("0");
        assertThat(service.billedCoverage(patientId, HOSPITAL_A, day)).contains(coverage);
    }

    @Test
    @DisplayName("QA D3250 (r70): the only coverage, never ticked primary, is billed by the same order the desk reads")
    void theOnlyCoverageIsBilledThoughNotPrimary() {
        coverage.setPrimary(false);
        UUID patientId = invoice.getPatient().getId();

        assertThat(service.billedCoverage(patientId, HOSPITAL_A, LocalDate.of(2026, 11, 22))).contains(coverage);
        assertThat(service.quote(patientId, HOSPITAL_B, LocalDate.of(2026, 11, 22), BigDecimal.TEN, null, null))
            .as("another hospital never quotes this coverage").isEmpty();
    }

    @Test
    @DisplayName("BC2 issued with RAMU 80 %, lab excluded: payers 5 600 (1 600 + 0 + 4 000), patient 2 900")
    void issuingAppliesTheCoverage() {
        BigDecimal applied = service.applyAtIssue(invoice, BILLING_A);

        assertThat(applied).isEqualByComparingTo("5600");
        assertThat(invoice.getPayerShareTotal()).isEqualByComparingTo("5600");
        assertThat(InvoiceBalance.patientShareOf(invoice)).isEqualByComparingTo("2900");
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("2900");
        assertThat(consultation.getPayerShareAmount()).isEqualByComparingTo("1600");
        assertThat(tdr.getPayerShareAmount()).isEqualByComparingTo("0");
        assertThat(xray.getPayerShareAmount()).isEqualByComparingTo("4000");
        InvoicePayerShare share = onlyShare();
        assertThat(share.getStatus()).isEqualTo(PayerShareStatus.OPEN);
        assertThat(share.getCoverageYear()).isEqualTo(2026);
        assertThat(share.getPatientInsuranceId()).isEqualTo(coverage.getId());
        assertThat(share.getHospitalId()).isEqualTo(HOSPITAL_A);
        // The annual ceiling was read under the coverage's row lock.
        verify(insurances).lockById(coverage.getId());
        ArgumentCaptor<AuditEventRequestDTO> audited = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(audited.capture());
        assertThat(audited.getValue().getEventType()).isEqualTo(AuditEventType.PAYER_SHARE_APPLIED);

        // A retried issue does not apply it twice.
        assertThat(service.applyAtIssue(invoice, BILLING_A)).isEqualByComparingTo("5600");
        assertThat(ledger).hasSize(1);
    }

    @Test
    @DisplayName("QA D2437: a draft previews the split issue would apply, per line, writing nothing")
    void aDraftPreviewsTheSplit() {
        invoice.setStatus(InvoiceStatus.DRAFT);

        CoverageCalculator.Result preview = service.previewForDraft(invoice).orElseThrow();

        assertThat(preview.total()).isEqualByComparingTo("5600");
        assertThat(preview.lines()).extracting(CoverageCalculator.LineShare::id)
            .contains(consultation.getId(), xray.getId());
        assertThat(preview.lines().stream().filter(l -> l.id().equals(xray.getId())).findFirst().orElseThrow()
            .share()).isEqualByComparingTo("4000");
        // Read-only: no lock, no share, nothing on the lines or the invoice, no audit row.
        verify(insurances, org.mockito.Mockito.never()).lockById(any());
        assertThat(ledger).isEmpty();
        assertThat(xray.getPayerShareAmount()).isEqualByComparingTo("0");
        assertThat(invoice.getPayerShareTotal()).isEqualByComparingTo("0");
        org.mockito.Mockito.verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("QA D2437: no preview on an issued invoice, a copay, or without coverage")
    void noPreviewOutsideADraft() {
        assertThat(service.previewForDraft(invoice)).isEmpty();
        invoice.setStatus(InvoiceStatus.DRAFT);
        when(insurances.findByPatient_IdAndAssignment_Hospital_Id(invoice.getPatient().getId(), HOSPITAL_A))
            .thenReturn(List.of());
        assertThat(service.previewForDraft(invoice)).isEmpty();
    }

    @Test
    @DisplayName("the annual ceiling: with 148 000 F used this year, the share is capped at 2 000")
    void theAnnualCeilingIsReadForThisCoverageAndYear() {
        when(shares.sumUsedInYear(coverage.getId(), HOSPITAL_A, 2026, invoice.getId()))
            .thenReturn(new BigDecimal("148000"));

        assertThat(service.applyAtIssue(invoice, BILLING_A)).isEqualByComparingTo("2000");
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("6500");
    }

    @Test
    @DisplayName("no coverage: a plan expired on the service date, a coverage expired, or no plan linked")
    void noCoverageWithoutAPlanInForceOnTheServiceDate() {
        plan.setEffectiveTo(SERVICE_DAY.minusDays(1));
        assertThat(service.applyAtIssue(invoice, BILLING_A)).isEqualByComparingTo("0");

        plan.setEffectiveTo(null);
        coverage.setExpirationDate(SERVICE_DAY.minusDays(1));
        assertThat(service.applyAtIssue(invoice, BILLING_A)).isEqualByComparingTo("0");

        coverage.setExpirationDate(null);
        coverage.setPayerPlanId(null);
        assertThat(service.applyAtIssue(invoice, BILLING_A)).isEqualByComparingTo("0");

        assertThat(ledger).isEmpty();
        assertThat(invoice.getPayerShareTotal()).isEqualByComparingTo("0");
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("8500");
    }

    @Test
    @DisplayName("the plan's terms that apply are the ones in force on the service date, not today's")
    void termsInForceOnTheServiceDate() {
        PayerPlanTerms later = PayerPlanTerms.builder().hospitalId(HOSPITAL_A).planId(plan.getId())
            .defaultCoveragePercent(50).effectiveFrom(SERVICE_DAY.plusDays(1)).build();
        PayerPlanTerms base = terms.findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(plan.getId(),
            HOSPITAL_A).get(0);
        base.setEffectiveTo(SERVICE_DAY);
        when(terms.findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(plan.getId(), HOSPITAL_A))
            .thenReturn(List.of(base, later));

        assertThat(service.applyAtIssue(invoice, BILLING_A)).isEqualByComparingTo("5600");
    }

    @Test
    @DisplayName("a rejection needs the payer's reason, never a card number, and moves 5 600 back to the patient")
    void rejectingNeedsTheReasonAndNeverACardNumber() {
        service.applyAtIssue(invoice, BILLING_A);
        invoice.setAmountPaid(new BigDecimal("2900"));
        invoice.setStatus(InvoiceStatus.PAID);
        UUID invoiceId = invoice.getId();
        UUID shareId = onlyShare().getId();

        assertThatThrownBy(() -> service.reject(invoiceId, shareId, "  "))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getFieldErrors()).containsOnlyKeys("reason"));
        assertThatThrownBy(() -> service.reject(invoiceId, shareId, "Carte 4111 1111 1111 1111"))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getFieldErrors()).containsOnlyKeys("reason"));

        List<InvoicePayerShareDTO> after = service.reject(invoiceId, shareId, "Bénéficiaire non à jour de cotisation");

        assertThat(after).singleElement().satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo("REJECTED");
            assertThat(dto.getAmount()).as("the history keeps the original share").isEqualByComparingTo("5600");
            assertThat(dto.getRejectionReason()).isEqualTo("Bénéficiaire non à jour de cotisation");
            assertThat(dto.getPayerName()).isEqualTo("RAMU");
            assertThat(dto.getPlanName()).isEqualTo("Formule Base");
        });
        assertThat(invoice.getPayerShareTotal()).isEqualByComparingTo("0");
        assertThat(InvoiceBalance.dueOf(invoice)).as("the balance rose by 5 600").isEqualByComparingTo("5600");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(xray.getPayerShareAmount()).isEqualByComparingTo("0");

        // Decided once: a second decision is refused with the share's status named.
        assertThatThrownBy(() -> service.markPaid(invoiceId, shareId)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("claim, then paid: the patient's balance does not move")
    void claimThenPaid() {
        service.applyAtIssue(invoice, BILLING_A);
        UUID shareId = onlyShare().getId();
        assertThat(service.claim(invoice.getId(), shareId)).singleElement()
            .extracting(InvoicePayerShareDTO::getStatus).isEqualTo("CLAIMED");
        assertThat(service.markPaid(invoice.getId(), shareId)).singleElement()
            .extracting(InvoicePayerShareDTO::getStatus).isEqualTo("PAID");
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("2900");
        verify(audit, atLeastOnce()).logEvent(any());
    }

    @Test
    @DisplayName("only the payer-share roles decide, at their hospital; another hospital's invoice is not found")
    void rolesAndTenancy() {
        service.applyAtIssue(invoice, BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID shareId = onlyShare().getId();

        when(roles.getCurrentUserId()).thenReturn(DOCTOR_A);
        assertThatThrownBy(() -> service.claim(invoiceId, shareId)).isInstanceOf(ClientSafeAccessDeniedException.class);

        when(roles.getCurrentUserId()).thenReturn(BILLING_A);
        when(scope.requirePinned()).thenReturn(HOSPITAL_B);
        when(roles.canDecidePayerShare(BILLING_A, HOSPITAL_B)).thenReturn(true);
        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL_B);
        assertThatThrownBy(() -> service.claim(invoiceId, shareId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.list(invoiceId)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(onlyShare().getStatus()).isEqualTo(PayerShareStatus.OPEN);
    }

    @Test
    @DisplayName("a credit note on a covered line replaces the open share; once claimed it is refused")
    void aCreditNoteRecomputesTheShare() {
        service.applyAtIssue(invoice, BILLING_A);
        InvoicePayerShare first = onlyShare();
        // Credit note of 1 000 on the X-ray: net 4 000, its share 3 200; the invoice share 4 800.
        creditNotes.put(xray.getId(), new BigDecimal("1000"));
        invoice.setTotalAmount(new BigDecimal("7500"));

        assertThat(service.recomputeAfterLineChange(invoice, BILLING_A)).isEqualByComparingTo("4800");

        assertThat(first.getStatus()).isEqualTo(PayerShareStatus.CANCELLED);
        InvoicePayerShare second = onlyShare();
        assertThat(second.getAmount()).isEqualByComparingTo("4800");
        assertThat(xray.getPayerShareAmount()).isEqualByComparingTo("3200");
        assertThat(InvoiceBalance.patientShareOf(invoice)).isEqualByComparingTo("2700");

        service.claim(invoice.getId(), second.getId());
        creditNotes.put(xray.getId(), new BigDecimal("2000"));
        invoice.setTotalAmount(new BigDecimal("6500"));
        assertThatThrownBy(() -> service.recomputeAfterLineChange(invoice, BILLING_A))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("cancelling the invoice cancels its open share (the ceiling is freed); a claimed share refuses it")
    void cancellingTheInvoice() {
        service.applyAtIssue(invoice, BILLING_A);
        service.cancelWithInvoice(invoice, BILLING_A);
        assertThat(ledger.values()).singleElement().extracting(InvoicePayerShare::getStatus)
            .isEqualTo(PayerShareStatus.CANCELLED);
        assertThat(invoice.getPayerShareTotal()).isEqualByComparingTo("0");

        BillingInvoice other = invoice(HOSPITAL_A);
        other.setPatient(invoice.getPatient());
        other.setTotalAmount(new BigDecimal("8500"));
        when(items.findByBillingInvoiceId(other.getId())).thenAnswer(call -> lines);
        service.applyAtIssue(other, BILLING_A);
        UUID shareId = ledger.values().stream().filter(s -> s.getInvoiceId().equals(other.getId())).findFirst()
            .orElseThrow().getId();
        service.claim(other.getId(), shareId);
        assertThatThrownBy(() -> service.cancelWithInvoice(other, BILLING_A)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the receipt's payer share is the one standing when the money came in")
    void theShareAtAMoment() {
        service.applyAtIssue(invoice, BILLING_A);
        InvoicePayerShare share = onlyShare();
        share.setCreatedAt(LocalDateTime.of(2026, 11, 17, 10, 0));
        assertThat(service.shareAt(invoice, LocalDateTime.of(2026, 11, 17, 9, 0))).isEqualByComparingTo("0");
        assertThat(service.shareAt(invoice, LocalDateTime.of(2026, 11, 17, 11, 0))).isEqualByComparingTo("5600");
        share.setStatus(PayerShareStatus.REJECTED);
        share.setDecidedAt(LocalDateTime.of(2026, 11, 17, 12, 0));
        assertThat(service.shareAt(invoice, LocalDateTime.of(2026, 11, 17, 11, 0))).isEqualByComparingTo("5600");
        assertThat(service.shareAt(invoice, LocalDateTime.of(2026, 11, 17, 13, 0))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a lab line that was not priced from the price list still falls under a lab rule")
    void aLabLineIsALabTestHoweverPriced() {
        assertThat(InvoiceCoverageService.categoryOf(
            InvoiceItem.builder().itemCategory(ItemCategory.LAB_TEST).build(), Map.of()))
            .isEqualTo(PriceListCategory.LAB_TEST);
        assertThat(InvoiceCoverageService.categoryOf(
            InvoiceItem.builder().itemCategory(ItemCategory.SERVICE).build(), Map.of())).isNull();
    }
}
