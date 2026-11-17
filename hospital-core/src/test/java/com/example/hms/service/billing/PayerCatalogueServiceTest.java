package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PayerType;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.Payer;
import com.example.hms.model.PayerPlan;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanRuleRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanUpdateRequestDTO;
import com.example.hms.payload.dto.billing.PayerReasonRequestDTO;
import com.example.hms.payload.dto.billing.PayerRequestDTO;
import com.example.hms.payload.dto.billing.PayerUpdateRequestDTO;
import com.example.hms.repository.PayerPlanRepository;
import com.example.hms.repository.PayerPlanRuleRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 6 (owner decision D-1): who writes and reads the payer catalogue, what each write refuses
 * (naming the field), and what it leaves in the audit (ids only). The role is checked on the assignment AT the acting
 * hospital before any lookup, so the super-admin alone, the cashier and a clinician never write. Fixtures are the
 * fictional payers of docs/plan/test-hospital (never a real insurer).
 */
@DisplayName("Payer catalogue: access, validation and audit")
class PayerCatalogueServiceTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private final PayerRepository payers = mock(PayerRepository.class);
    private final PayerPlanRepository plans = mock(PayerPlanRepository.class);
    private final PayerPlanRuleRepository rules = mock(PayerPlanRuleRepository.class);
    private final PriceListItemRepository items = mock(PriceListItemRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private PayerCatalogueService service;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        service = new PayerCatalogueService(payers, plans, rules, items, mock(UserRepository.class), roles, scope,
            audit, CLOCK, "Africa/Ouagadougou");
        when(scope.requirePinned()).thenReturn(HOSPITAL);
        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL);
        when(roles.getCurrentUserId()).thenReturn(ACTOR);
        when(payers.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0)));
        when(plans.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0)));
        when(rules.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0)));
    }

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static <E extends BaseEntity> E withId(E entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        return entity;
    }

    private void maintainer() {
        when(roles.canMaintainPayers(ACTOR, HOSPITAL)).thenReturn(true);
    }

    private Payer payer(boolean active) {
        Payer payer = withId(Payer.builder().hospitalId(HOSPITAL).code("MUT-TEST-A")
            .name("Mutuelle Test A (FICTIVE)").payerType(PayerType.MUTUELLE).active(active).build());
        when(payers.lockByIdAtHospital(payer.getId(), HOSPITAL)).thenReturn(Optional.of(payer));
        return payer;
    }

    private PayerPlan plan(Payer payer, LocalDate from, LocalDate to) {
        PayerPlan plan = withId(PayerPlan.builder().hospitalId(HOSPITAL).payerId(payer.getId()).code("MTA-ESS")
            .name("Formule Test Essentielle").defaultCoveragePercent(75).effectiveFrom(from).effectiveTo(to).build());
        when(plans.findByIdAndPayerIdAndHospitalId(plan.getId(), payer.getId(), HOSPITAL))
            .thenReturn(Optional.of(plan));
        return plan;
    }

    private static PayerPlanRequestDTO basePlan() {
        return PayerPlanRequestDTO.builder().code("AMU-BASE").name("Formule Base")
            .defaultCoveragePercent(new BigDecimal("80")).annualCeilingPerBeneficiary(new BigDecimal("150000"))
            .build();
    }

    private AuditEventRequestDTO auditRow() {
        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(row.capture());
        return row.getValue();
    }

    // ───────────────────────────── access ─────────────────────────────

    @Test
    @DisplayName("without HOSPITAL_ADMIN or ACCOUNTANT at the hospital (super-admin alone, cashier, doctor) nothing is written")
    void aCallerWithoutAMaintainerAssignmentIsRefusedBeforeAnyLookup() {
        when(roles.canMaintainPayers(ACTOR, HOSPITAL)).thenReturn(false);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.createPayer(PayerRequestDTO.builder().code("X").name("X")
            .payerType(PayerType.MUTUELLE).build()))
            .isInstanceOf(ClientSafeAccessDeniedException.class)
            .hasMessage("Seuls l’administrateur de l’établissement et le comptable modifient les organismes payeurs "
                + "et leurs formules.");
        assertThatThrownBy(() -> service.updatePayer(id, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.deactivatePayer(id, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.createPlan(id, basePlan())).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.updatePlan(id, id, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.addRule(id, id, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.endRule(id, id, id, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        verifyNoInteractions(payers, plans, rules, audit);
    }

    @Test
    @DisplayName("the super-admin's global view (no hospital named) never writes")
    void globalViewNeverWrites() {
        when(scope.requirePinned()).thenThrow(new HospitalScopeRefusedException("Select a hospital"));
        assertThatThrownBy(() -> service.createPayer(new PayerRequestDTO()))
            .isInstanceOf(HospitalScopeRefusedException.class);
        verifyNoInteractions(payers, plans, rules, audit);
        verify(roles, never()).canMaintainPayers(any(), any());
    }

    @Test
    @DisplayName("a doctor does not read the catalogue; the billing specialist and reception do")
    void readersHoldAReadingRoleAtTheHospital() {
        when(scope.isVerifiedSuperAdmin()).thenReturn(false);
        when(roles.canReadPayers(ACTOR, HOSPITAL)).thenReturn(false);
        assertThatThrownBy(() -> service.list(false)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(payers);

        when(roles.canReadPayers(ACTOR, HOSPITAL)).thenReturn(true);
        Payer payer = payer(true);
        when(payers.findByHospitalIdOrderByCodeAsc(HOSPITAL)).thenReturn(List.of(payer));
        assertThat(service.list(false)).extracting("code").containsExactly("MUT-TEST-A");
    }

    @Test
    @DisplayName("no hospital and not the verified super-admin: refused, never read across hospitals")
    void aNullHospitalWithoutTheVerifiedSuperAdminIsRefused() {
        when(roles.requireActiveHospitalId()).thenReturn(null);
        when(scope.isVerifiedSuperAdmin()).thenReturn(false);
        assertThatThrownBy(() -> service.list(true)).isInstanceOf(AccessDeniedException.class);
        verify(payers, never()).findAllByOrderByCodeAsc();
    }

    @Test
    @DisplayName("another hospital's payer answers 404, as a missing one")
    void anotherHospitalsPayerIsNotFound() {
        maintainer();
        UUID foreign = UUID.randomUUID();
        when(payers.lockByIdAtHospital(foreign, HOSPITAL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPlan(foreign, basePlan())).isInstanceOf(ResourceNotFoundException.class);
        when(roles.canReadPayers(ACTOR, HOSPITAL)).thenReturn(true);
        when(payers.findByIdAndHospitalId(foreign, HOSPITAL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(foreign)).isInstanceOf(ResourceNotFoundException.class);
        verify(plans, never()).saveAndFlush(any());
    }

    // ───────────────────────────── payers ─────────────────────────────

    @Test
    @DisplayName("an accountant creates a payer; the code is upper-cased and the audit row carries ids only")
    void anAccountantCreatesAPayer() {
        maintainer();
        var created = service.createPayer(PayerRequestDTO.builder().code(" mut-test-a ")
            .name("Mutuelle Test A (FICTIVE)").payerType(PayerType.MUTUELLE).build());

        assertThat(created.getCode()).isEqualTo("MUT-TEST-A");
        assertThat(created.getHospitalId()).isEqualTo(HOSPITAL);
        AuditEventRequestDTO row = auditRow();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.PAYER_CHANGED);
        assertThat(row.getHospitalId()).isEqualTo(HOSPITAL);
        assertThat(row.getUserId()).isEqualTo(ACTOR);
        assertThat(row.getResourceId()).isEqualTo(created.getId().toString());
        assertThat(row.getEventDescription()).doesNotContain("Mutuelle", "MUT-TEST-A");
        assertThat(row.getPatientId()).isNull();
    }

    @Test
    @DisplayName("an empty payer is refused once, naming code, name and type; a duplicate code names the code")
    void aPayerNamesEveryRefusedField() {
        maintainer();
        assertThatThrownBy(() -> service.createPayer(new PayerRequestDTO()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getFieldErrors())
                .containsOnlyKeys("code", "name", "payerType"));
        when(payers.existsByHospitalIdAndCode(HOSPITAL, "MUT-TEST-A")).thenReturn(true);
        assertThatThrownBy(() -> service.createPayer(PayerRequestDTO.builder().code("MUT-TEST-A").name("A")
            .payerType(PayerType.MUTUELLE).build()))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("code");
                assertThat(e.getMessage()).contains("MUT-TEST-A");
            });
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a payer is renamed and retyped, and the edit is audited; a deactivated payer takes no edit")
    void updatePayerEditsNameAndTypeAndRefusesADeactivatedPayer() {
        maintainer();
        Payer payer = payer(true);
        service.updatePayer(payer.getId(), PayerUpdateRequestDTO.builder().name("Mutuelle Test A")
            .payerType(PayerType.ASSUREUR_PRIVE).build());
        assertThat(payer.getName()).isEqualTo("Mutuelle Test A");
        assertThat(payer.getPayerType()).isEqualTo(PayerType.ASSUREUR_PRIVE);
        assertThat(payer.getCode()).isEqualTo("MUT-TEST-A");
        assertThat(auditRow().getEventType()).isEqualTo(AuditEventType.PAYER_CHANGED);

        Payer closed = payer(false);
        assertThatThrownBy(() -> service.updatePayer(closed.getId(), PayerUpdateRequestDTO.builder().name("B")
            .payerType(PayerType.MUTUELLE).build())).isInstanceOf(BusinessException.class);
        assertThat(closed.getName()).isEqualTo("Mutuelle Test A (FICTIVE)");
    }

    @Test
    @DisplayName("deactivating a payer needs a reason, and keeps who, when and why")
    void deactivatingAPayerNeedsAReason() {
        maintainer();
        Payer payer = payer(true);
        assertThatThrownBy(() -> service.deactivatePayer(payer.getId(), new PayerReasonRequestDTO(" ")))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("reason"));
        assertThat(payer.isActive()).isTrue();

        service.deactivatePayer(payer.getId(), new PayerReasonRequestDTO("Convention résiliée"));
        assertThat(payer.isActive()).isFalse();
        assertThat(payer.getDeactivatedBy()).isEqualTo(ACTOR);
        assertThat(payer.getDeactivationReason()).isEqualTo("Convention résiliée");
        assertThat(auditRow().getEventDescription()).doesNotContain("Convention");
    }

    // ───────────────────────────── plans ─────────────────────────────

    @Test
    @DisplayName("ACCOUNTANT_A creates Formule Base 80 %, annual ceiling 150 000 F; the audit row carries ids only")
    void anAccountantCreatesAPlan() {
        maintainer();
        Payer payer = payer(true);
        service.createPlan(payer.getId(), basePlan());

        ArgumentCaptor<PayerPlan> saved = ArgumentCaptor.forClass(PayerPlan.class);
        verify(plans).saveAndFlush(saved.capture());
        PayerPlan plan = saved.getValue();
        assertThat(plan.getCode()).isEqualTo("AMU-BASE");
        assertThat(plan.getDefaultCoveragePercent()).isEqualTo(80);
        assertThat(plan.getAnnualCeilingPerBeneficiary()).isEqualByComparingTo("150000");
        assertThat(plan.getPerInvoiceCeiling()).isNull();
        assertThat(plan.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(plan.getHospitalId()).isEqualTo(HOSPITAL);
        AuditEventRequestDTO row = auditRow();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.PAYER_PLAN_CHANGED);
        // Ids only: the sentence is fixed, and holds no name, rate or ceiling.
        assertThat(row.getEventDescription())
            .matches("Payer plan [0-9a-f-]{36} of payer [0-9a-f-]{36} created");
    }

    @Test
    @DisplayName("a rate outside 0-100 or a decimal ceiling is refused, naming the field")
    void aPlanRefusesABadRateOrADecimalCeiling() {
        maintainer();
        Payer payer = payer(true);
        PayerPlanRequestDTO tooHigh = basePlan();
        tooHigh.setDefaultCoveragePercent(new BigDecimal("101"));
        assertThatThrownBy(() -> service.createPlan(payer.getId(), tooHigh))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("defaultCoveragePercent"));
        PayerPlanRequestDTO decimal = basePlan();
        decimal.setAnnualCeilingPerBeneficiary(new BigDecimal("150000.5"));
        assertThatThrownBy(() -> service.createPlan(payer.getId(), decimal))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("annualCeilingPerBeneficiary"));
        PayerPlanRequestDTO reversed = basePlan();
        reversed.setEffectiveFrom(TODAY.plusDays(16));
        reversed.setEffectiveTo(TODAY.plusDays(10));
        assertThatThrownBy(() -> service.createPlan(payer.getId(), reversed))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("effectiveTo"));
        verify(plans, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a started plan keeps its start, may end from today, and an ended plan takes no change")
    void updatePlanRules() {
        maintainer();
        Payer payer = payer(true);
        PayerPlan started = plan(payer, TODAY.minusDays(30), null);

        assertThatThrownBy(() -> service.updatePlan(payer.getId(), started.getId(), PayerPlanUpdateRequestDTO.builder()
            .name("Formule").defaultCoveragePercent(BigDecimal.TEN).effectiveFrom(TODAY.plusDays(1)).build()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("effectiveFrom"));
        assertThatThrownBy(() -> service.updatePlan(payer.getId(), started.getId(), PayerPlanUpdateRequestDTO.builder()
            .name("Formule").defaultCoveragePercent(BigDecimal.TEN).effectiveTo(TODAY.minusDays(5)).build()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("effectiveTo"));

        service.updatePlan(payer.getId(), started.getId(), PayerPlanUpdateRequestDTO.builder()
            .name("Formule Test Essentielle").defaultCoveragePercent(new BigDecimal("70"))
            .effectiveTo(TODAY.minusDays(1)).build());
        assertThat(started.getDefaultCoveragePercent()).isEqualTo(70);
        assertThat(started.getEffectiveTo()).isEqualTo(TODAY.minusDays(1));
        assertThat(started.getEffectiveFrom()).isEqualTo(TODAY.minusDays(30));

        assertThatThrownBy(() -> service.updatePlan(payer.getId(), started.getId(), PayerPlanUpdateRequestDTO.builder()
            .name("Formule").defaultCoveragePercent(BigDecimal.TEN).build()))
            .isInstanceOf(BusinessException.class);
    }

    // ───────────────────────────── rules ─────────────────────────────

    @Test
    @DisplayName("a rule naming both a category and an item is refused, naming the field; nothing is written")
    void aRuleNamingBothTargetsIsRefused() {
        maintainer();
        Payer payer = payer(true);
        PayerPlan plan = plan(payer, TODAY, null);
        assertThatThrownBy(() -> service.addRule(payer.getId(), plan.getId(), PayerPlanRuleRequestDTO.builder()
            .category(PriceListCategory.LAB_TEST).priceListItemId(UUID.randomUUID()).excluded(true).build()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("priceListItemId"));
        verify(rules, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an item rule names an item of the acting hospital: another hospital's answers as an unknown one")
    void anItemRuleNamesAnItemOfTheHospital() {
        maintainer();
        Payer payer = payer(true);
        PayerPlan plan = plan(payer, TODAY, null);
        UUID foreignItem = UUID.randomUUID();
        when(items.findByIdAndHospitalId(foreignItem, HOSPITAL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addRule(payer.getId(), plan.getId(), PayerPlanRuleRequestDTO.builder()
            .priceListItemId(foreignItem).excluded(true).build()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("priceListItemId"));
    }

    @Test
    @DisplayName("laboratory excluded: the rule is written from today and audited with ids only")
    void aCategoryExclusionIsWritten() {
        maintainer();
        Payer payer = payer(true);
        PayerPlan plan = plan(payer, TODAY, null);
        service.addRule(payer.getId(), plan.getId(), PayerPlanRuleRequestDTO.builder()
            .category(PriceListCategory.LAB_TEST).excluded(true).build());

        ArgumentCaptor<PayerPlanRule> saved = ArgumentCaptor.forClass(PayerPlanRule.class);
        verify(rules).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo(PriceListCategory.LAB_TEST);
        assertThat(saved.getValue().isExcluded()).isTrue();
        assertThat(saved.getValue().getCoveragePercent()).isNull();
        assertThat(saved.getValue().getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(saved.getValue().getHospitalId()).isEqualTo(HOSPITAL);
        AuditEventRequestDTO row = auditRow();
        assertThat(row.getEventType()).isEqualTo(AuditEventType.PAYER_PLAN_CHANGED);
        assertThat(row.getEventDescription()).contains(plan.getId().toString()).doesNotContain("LAB_TEST");
    }

    @Test
    @DisplayName("a new rule for the same target closes the one in force on the day before it starts")
    void aNewRuleClosesTheOneInForce() {
        maintainer();
        Payer payer = payer(true);
        PayerPlan plan = plan(payer, TODAY.minusDays(60), null);
        PayerPlanRule inForce = withId(PayerPlanRule.builder().hospitalId(HOSPITAL).planId(plan.getId())
            .category(PriceListCategory.IMAGING).coveragePercent(50).effectiveFrom(TODAY.minusDays(60)).build());
        when(rules.findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(plan.getId(), HOSPITAL))
            .thenReturn(List.of(inForce));

        service.addRule(payer.getId(), plan.getId(), PayerPlanRuleRequestDTO.builder()
            .category(PriceListCategory.IMAGING).coveragePercent(new BigDecimal("60"))
            .effectiveFrom(TODAY.plusDays(16)).build());

        assertThat(inForce.getEffectiveTo()).isEqualTo(TODAY.plusDays(15));
        assertThat(inForce.getEndedBy()).isNull();
    }

    @Test
    @DisplayName("ending a rule needs a reason, keeps who, when and why, and a second end is refused")
    void endingARuleNeedsAReason() {
        maintainer();
        Payer payer = payer(true);
        PayerPlan plan = plan(payer, TODAY.minusDays(60), null);
        PayerPlanRule rule = withId(PayerPlanRule.builder().hospitalId(HOSPITAL).planId(plan.getId())
            .category(PriceListCategory.LAB_TEST).excluded(true).effectiveFrom(TODAY.minusDays(60)).build());
        when(rules.findByIdAndPlanIdAndHospitalId(rule.getId(), plan.getId(), HOSPITAL)).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.endRule(payer.getId(), plan.getId(), rule.getId(),
            new PayerReasonRequestDTO("")))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("reason"));
        assertThat(rule.getEffectiveTo()).isNull();

        service.endRule(payer.getId(), plan.getId(), rule.getId(), new PayerReasonRequestDTO("Avenant 2026"));
        assertThat(rule.getEffectiveTo()).isEqualTo(TODAY.minusDays(1));
        assertThat(rule.getEndedBy()).isEqualTo(ACTOR);
        assertThat(rule.getEndReason()).isEqualTo("Avenant 2026");
        assertThat(auditRow().getEventDescription()).doesNotContain("Avenant");

        assertThatThrownBy(() -> service.endRule(payer.getId(), plan.getId(), rule.getId(),
            new PayerReasonRequestDTO("Autre motif"))).isInstanceOf(BusinessException.class);
        assertThat(rule.getEndReason()).isEqualTo("Avenant 2026");
    }

    // ───────────────────────────── coverage link ─────────────────────────────

    @Test
    @DisplayName("a plan of another hospital answers 404; an ended plan or a deactivated payer is not linkable")
    void onlyALiveplanOfTheHospitalIsLinkable() {
        UUID foreign = UUID.randomUUID();
        when(plans.findByIdAndHospitalId(foreign, HOSPITAL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireLinkablePlan(foreign, HOSPITAL))
            .isInstanceOf(ResourceNotFoundException.class);

        Payer payer = payer(true);
        when(payers.findByIdAndHospitalId(payer.getId(), HOSPITAL)).thenReturn(Optional.of(payer));
        PayerPlan live = plan(payer, TODAY.plusDays(16), null);
        when(plans.findByIdAndHospitalId(live.getId(), HOSPITAL)).thenReturn(Optional.of(live));
        assertThat(service.requireLinkablePlan(live.getId(), HOSPITAL)).isSameAs(live);

        PayerPlan ended = plan(payer, TODAY.minusDays(30), TODAY.minusDays(1));
        when(plans.findByIdAndHospitalId(ended.getId(), HOSPITAL)).thenReturn(Optional.of(ended));
        assertThatThrownBy(() -> service.requireLinkablePlan(ended.getId(), HOSPITAL))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("payerPlanId"));

        payer.setActive(false);
        assertThatThrownBy(() -> service.requireLinkablePlan(live.getId(), HOSPITAL))
            .isInstanceOf(FieldBusinessException.class);
    }
}
