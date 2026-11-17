package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Payer;
import com.example.hms.model.PayerPlan;
import com.example.hms.model.PayerPlanRule;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PayerDTO;
import com.example.hms.payload.dto.billing.PayerPlanDTO;
import com.example.hms.payload.dto.billing.PayerPlanRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanRuleDTO;
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
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Billing-money plan PR 6 (V305, scorecard M1): each hospital's payer catalogue - payers, their plans ("formules")
 * and the plans' rules - and the one check a patient's coverage passes to be linked to a plan.
 *
 * <p><b>Who.</b> Reading: the hospital admin, the accountant, the billing specialist, reception (to pick a plan) and
 * the claims reviewer, at their acting hospital; the verified platform super-admin reads any hospital (one named with
 * X-Hospital-Id, or all of them in global view). Writing: only a HOSPITAL_ADMIN or ACCOUNTANT assignment AT the acting
 * hospital (owner decision D-1, 2026-11-15). The cashier, a clinician and the super-admin on their own are refused,
 * whatever their authorities say ({@code RoleExpansion} grants a super-admin the hospital admin authority, so the role
 * is checked on the assignment table here, not by {@code @PreAuthorize} alone).
 *
 * <p><b>Tenancy.</b> Every payer, plan and rule carries its hospital, and the composite foreign keys (V305) hold a
 * plan to its payer's hospital and a rule to its plan's and its item's. A row of another hospital answers exactly as a
 * missing one (404); the role is checked before any lookup.
 *
 * <p><b>History.</b> A rule changes only by adding one (see {@link PayerCoverageRules}); the one update is closing
 * {@code effective_to}. A plan's name, rate and ceilings are edited in place and audited: nothing is applied to an
 * invoice yet, and PR 7 will copy the payer's share onto the invoice when it is issued, so an edit never rewrites an
 * issued invoice.
 *
 * <p><b>Audit.</b> Every write leaves one row after commit, ids only (no name, no rate, no reason text).
 */
@Slf4j
@Service
public class PayerCatalogueService {

    static final String PAYER_NOT_FOUND_KEY = "billing.payer.notFound";
    static final String PLAN_NOT_FOUND_KEY = "billing.payerPlan.notFound";
    static final String RULE_NOT_FOUND_KEY = "billing.payerRule.notFound";
    static final String AUDIT_ENTITY_PAYER = "PAYER";
    private static final String PAYER_PREFIX = "Payer ";
    static final String AUDIT_ENTITY_PLAN = "PAYER_PLAN";
    static final String AUDIT_ENTITY_COVERAGE = "PATIENT_INSURANCE";
    static final int NAME_MAX = 150;
    static final int REASON_MAX = 500;
    static final int CODE_MAX = 40;
    /** Letters, digits, dot, dash, underscore; starts with a letter or digit (so never a spreadsheet formula). */
    static final Pattern CODE_FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,39}");

    static final String FIELD_CODE = "code";
    static final String FIELD_NAME = "name";
    static final String FIELD_PAYER_TYPE = "payerType";
    static final String FIELD_DEFAULT_PERCENT = "defaultCoveragePercent";
    static final String FIELD_INVOICE_CEILING = "perInvoiceCeiling";
    static final String FIELD_ANNUAL_CEILING = "annualCeilingPerBeneficiary";
    static final String FIELD_REASON = "reason";
    static final String FIELD_PAYER_PLAN = "payerPlanId";

    private final PayerRepository payerRepository;
    private final PayerPlanRepository planRepository;
    private final PayerPlanRuleRepository ruleRepository;
    private final PriceListItemRepository priceListItemRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;
    private final ZoneId businessZone;

    @SuppressWarnings("java:S107") // one collaborator per concern; a parameter object would only hide them
    public PayerCatalogueService(PayerRepository payerRepository,
                                 PayerPlanRepository planRepository,
                                 PayerPlanRuleRepository ruleRepository,
                                 PriceListItemRepository priceListItemRepository,
                                 UserRepository userRepository,
                                 RoleValidator roleValidator,
                                 ActingScopeResolver actingScopeResolver,
                                 AuditEventLogService auditEventLogService,
                                 Clock clock,
                                 @Value("${app.billing.business-zone:Africa/Ouagadougou}") String businessZone) {
        this.payerRepository = payerRepository;
        this.planRepository = planRepository;
        this.ruleRepository = ruleRepository;
        this.priceListItemRepository = priceListItemRepository;
        this.userRepository = userRepository;
        this.roleValidator = roleValidator;
        this.actingScopeResolver = actingScopeResolver;
        this.auditEventLogService = auditEventLogService;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    // ───────────────────────────── reads ─────────────────────────────

    /** The acting hospital's payers with their plans and rules (every hospital's in the super-admin's global view). */
    @Transactional(readOnly = true)
    public List<PayerDTO> list(boolean includeInactive) {
        UUID hospitalId = requireReader();
        List<Payer> payers = (hospitalId == null
            ? payerRepository.findAllByOrderByCodeAsc()
            : payerRepository.findByHospitalIdOrderByCodeAsc(hospitalId)).stream()
            .filter(payer -> includeInactive || payer.isActive())
            .toList();
        return toDtos(payers);
    }

    /** One payer with its plans and their rules (history included). */
    @Transactional(readOnly = true)
    public PayerDTO get(UUID payerId) {
        UUID hospitalId = requireReader();
        Payer payer = (hospitalId == null
            ? payerRepository.findById(payerId)
            : payerRepository.findByIdAndHospitalId(payerId, hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException(PAYER_NOT_FOUND_KEY, payerId));
        return toDtos(List.of(payer)).get(0);
    }

    // ───────────────────────────── payers ─────────────────────────────

    @Transactional
    public PayerDTO createPayer(PayerRequestDTO request) {
        Maintainer actor = requireMaintainer();
        PayerRequestDTO body = request != null ? request : new PayerRequestDTO();
        String code = normalisedCode(body.getCode());
        String name = trimmed(body.getName());
        RequiredFields.check()
            .require(!code.isEmpty(), FIELD_CODE, "billing.payer.codeRequired")
            .require(!name.isEmpty(), FIELD_NAME, "billing.payer.nameRequired")
            .require(body.getPayerType() != null, FIELD_PAYER_TYPE, "billing.payer.typeRequired")
            .orThrow();
        requireCodeFormat(code);
        requireNameLength(name);
        if (payerRepository.existsByHospitalIdAndCode(actor.hospitalId(), code)) {
            throw new FieldBusinessException(FIELD_CODE, "billing.payer.codeDuplicate", code);
        }
        Payer saved;
        try {
            saved = payerRepository.saveAndFlush(Payer.builder()
                .hospitalId(actor.hospitalId())
                .code(code)
                .name(name)
                .payerType(body.getPayerType())
                .active(true)
                .createdBy(actor.userId())
                .updatedBy(actor.userId())
                .build());
        } catch (DataIntegrityViolationException race) {
            // A concurrent create of the same code at this hospital won between the check and the insert.
            throw new FieldBusinessException(FIELD_CODE, "billing.payer.codeDuplicate", code);
        }
        audit(AuditEventType.PAYER_CHANGED, actor, PAYER_PREFIX + saved.getId() + " created", AUDIT_ENTITY_PAYER,
            saved.getId());
        return toDtos(List.of(saved)).get(0);
    }

    @Transactional
    public PayerDTO updatePayer(UUID payerId, PayerUpdateRequestDTO request) {
        Maintainer actor = requireMaintainer();
        Payer payer = lockPayer(payerId, actor);
        requireNotWithdrawn(payer);
        PayerUpdateRequestDTO body = request != null ? request : new PayerUpdateRequestDTO();
        String name = trimmed(body.getName());
        RequiredFields.check()
            .require(!name.isEmpty(), FIELD_NAME, "billing.payer.nameRequired")
            .require(body.getPayerType() != null, FIELD_PAYER_TYPE, "billing.payer.typeRequired")
            .orThrow();
        requireNameLength(name);
        payer.setName(name);
        payer.setPayerType(body.getPayerType());
        payer.setUpdatedBy(actor.userId());
        payerRepository.save(payer);
        audit(AuditEventType.PAYER_CHANGED, actor, PAYER_PREFIX + payer.getId() + " updated", AUDIT_ENTITY_PAYER,
            payer.getId());
        return toDtos(List.of(payer)).get(0);
    }

    /** The payer's plans are no longer linked to anyone new; its history and existing links stay. */
    @Transactional
    public PayerDTO deactivatePayer(UUID payerId, PayerReasonRequestDTO request) {
        Maintainer actor = requireMaintainer();
        Payer payer = lockPayer(payerId, actor);
        if (payer.isActive()) {
            String reason = requiredReason(request, "billing.payer.deactivationReasonRequired");
            payer.setActive(false);
            payer.setDeactivatedAt(LocalDateTime.now(clock));
            payer.setDeactivatedBy(actor.userId());
            payer.setDeactivationReason(reason);
            payer.setUpdatedBy(actor.userId());
            payerRepository.save(payer);
            audit(AuditEventType.PAYER_CHANGED, actor, PAYER_PREFIX + payer.getId() + " deactivated", AUDIT_ENTITY_PAYER,
                payer.getId());
        }
        return toDtos(List.of(payer)).get(0);
    }

    // ───────────────────────────── plans ─────────────────────────────

    @Transactional
    public PayerDTO createPlan(UUID payerId, PayerPlanRequestDTO request) {
        Maintainer actor = requireMaintainer();
        Payer payer = lockPayer(payerId, actor);
        requireNotWithdrawn(payer);
        PayerPlanRequestDTO body = request != null ? request : new PayerPlanRequestDTO();
        String code = normalisedCode(body.getCode());
        String name = trimmed(body.getName());
        RequiredFields.check()
            .require(!code.isEmpty(), FIELD_CODE, "billing.payerPlan.codeRequired")
            .require(!name.isEmpty(), FIELD_NAME, "billing.payerPlan.nameRequired")
            .require(body.getDefaultCoveragePercent() != null, FIELD_DEFAULT_PERCENT,
                "billing.payerPlan.percentRequired")
            .orThrow();
        requireCodeFormat(code);
        requireNameLength(name);
        Terms terms = termsOf(body.getDefaultCoveragePercent(), body.getPerInvoiceCeiling(),
            body.getAnnualCeilingPerBeneficiary());
        LocalDate today = today();
        LocalDate from = PayerCoverageRules.startOf(body.getEffectiveFrom(), today);
        LocalDate to = body.getEffectiveTo();
        if (to != null && to.isBefore(from)) {
            throw new FieldBusinessException(PayerCoverageRules.FIELD_EFFECTIVE_TO, "billing.payerPlan.endBeforeStart");
        }
        if (planRepository.existsByPayerIdAndCode(payer.getId(), code)) {
            throw new FieldBusinessException(FIELD_CODE, "billing.payerPlan.codeDuplicate", code);
        }
        PayerPlan saved;
        try {
            saved = planRepository.saveAndFlush(PayerPlan.builder()
                .hospitalId(actor.hospitalId())
                .payerId(payer.getId())
                .code(code)
                .name(name)
                .defaultCoveragePercent(terms.percent())
                .perInvoiceCeiling(terms.perInvoice())
                .annualCeilingPerBeneficiary(terms.annual())
                .effectiveFrom(from)
                .effectiveTo(to)
                .createdBy(actor.userId())
                .updatedBy(actor.userId())
                .build());
        } catch (DataIntegrityViolationException race) {
            throw new FieldBusinessException(FIELD_CODE, "billing.payerPlan.codeDuplicate", code);
        }
        audit(AuditEventType.PAYER_PLAN_CHANGED, actor,
            "Payer plan " + saved.getId() + " of payer " + payer.getId() + " created", AUDIT_ENTITY_PLAN, saved.getId());
        return toDtos(List.of(payer)).get(0);
    }

    /**
     * The plan's name, rate, ceilings and dates; never its code. The start moves only while the plan has not
     * started, and never into the past; the plan may be ended from today at the earliest. An ended plan takes no
     * change.
     */
    @Transactional
    public PayerDTO updatePlan(UUID payerId, UUID planId, PayerPlanUpdateRequestDTO request) {
        Maintainer actor = requireMaintainer();
        Payer payer = lockPayer(payerId, actor);
        requireNotWithdrawn(payer);
        PayerPlan plan = planAtPayer(planId, payer, actor);
        LocalDate today = today();
        if (plan.endedBefore(today)) {
            throw new BusinessException("billing.payerPlan.ended");
        }
        PayerPlanUpdateRequestDTO body = request != null ? request : new PayerPlanUpdateRequestDTO();
        String name = trimmed(body.getName());
        RequiredFields.check()
            .require(!name.isEmpty(), FIELD_NAME, "billing.payerPlan.nameRequired")
            .require(body.getDefaultCoveragePercent() != null, FIELD_DEFAULT_PERCENT,
                "billing.payerPlan.percentRequired")
            .orThrow();
        requireNameLength(name);
        Terms terms = termsOf(body.getDefaultCoveragePercent(), body.getPerInvoiceCeiling(),
            body.getAnnualCeilingPerBeneficiary());
        LocalDate from = plan.getEffectiveFrom();
        if (body.getEffectiveFrom() != null && !body.getEffectiveFrom().equals(from)) {
            if (!from.isAfter(today)) {
                throw new FieldBusinessException(PayerCoverageRules.FIELD_EFFECTIVE_FROM,
                    "billing.payerPlan.startedStartFixed");
            }
            from = PayerCoverageRules.startOf(body.getEffectiveFrom(), today);
        }
        LocalDate to = body.getEffectiveTo();
        // The last day may be yesterday at the earliest (the plan stops today), or the day before its own start
        // (withdrawn before it started).
        if (to != null && !to.equals(plan.getEffectiveTo()) && to.isBefore(today.minusDays(1))) {
            throw new FieldBusinessException(PayerCoverageRules.FIELD_EFFECTIVE_TO, "billing.payerPlan.endInPast");
        }
        if (to != null && to.isBefore(from.minusDays(1))) {
            throw new FieldBusinessException(PayerCoverageRules.FIELD_EFFECTIVE_TO, "billing.payerPlan.endBeforeStart");
        }
        plan.setName(name);
        plan.setDefaultCoveragePercent(terms.percent());
        plan.setPerInvoiceCeiling(terms.perInvoice());
        plan.setAnnualCeilingPerBeneficiary(terms.annual());
        plan.setEffectiveFrom(from);
        plan.setEffectiveTo(to);
        plan.setUpdatedBy(actor.userId());
        planRepository.save(plan);
        audit(AuditEventType.PAYER_PLAN_CHANGED, actor,
            "Payer plan " + plan.getId() + " of payer " + payer.getId() + " updated", AUDIT_ENTITY_PLAN, plan.getId());
        return toDtos(List.of(payer)).get(0);
    }

    // ───────────────────────────── rules ─────────────────────────────

    /**
     * A new rule. Under the payer's lock: the rule in force for the same target is closed on the day before the new
     * one starts, and a rule planned for that target on or after the new start is replaced only when the caller
     * confirms it.
     */
    @Transactional
    public PayerDTO addRule(UUID payerId, UUID planId, PayerPlanRuleRequestDTO request) {
        Maintainer actor = requireMaintainer();
        Payer payer = lockPayer(payerId, actor);
        requireNotWithdrawn(payer);
        PayerPlan plan = planAtPayer(planId, payer, actor);
        LocalDate today = today();
        if (plan.endedBefore(today)) {
            throw new BusinessException("billing.payerPlan.ended");
        }
        PayerPlanRuleRequestDTO body = request != null ? request : new PayerPlanRuleRequestDTO();
        PayerCoverageRules.requireOneTarget(body.getCategory(), body.getPriceListItemId());
        Integer percent = PayerCoverageRules.percentOf(body.getCoveragePercent(),
            PayerCoverageRules.FIELD_COVERAGE_PERCENT);
        BigDecimal perAct = PayerCoverageRules.ceilingOf(body.getPerActCeiling(),
            PayerCoverageRules.FIELD_PER_ACT_CEILING);
        PayerCoverageRules.requireCoherentTerms(body.isExcluded(), percent, perAct);
        if (body.getPriceListItemId() != null
            && priceListItemRepository.findByIdAndHospitalId(body.getPriceListItemId(), actor.hospitalId()).isEmpty()) {
            // Another hospital's item answers exactly as a made-up one.
            throw new FieldBusinessException(PayerCoverageRules.FIELD_ITEM, "billing.payer.ruleItemUnknown");
        }
        LocalDate from = PayerCoverageRules.startOf(body.getEffectiveFrom(), today);
        if (plan.getEffectiveTo() != null && from.isAfter(plan.getEffectiveTo())) {
            throw new FieldBusinessException(PayerCoverageRules.FIELD_EFFECTIVE_FROM, "billing.payer.ruleAfterPlanEnd");
        }
        List<PayerPlanRule> sameTarget = ruleRepository
            .findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(plan.getId(), actor.hospitalId()).stream()
            .filter(rule -> rule.hasTarget(body.getCategory(), body.getPriceListItemId()))
            .toList();
        PayerCoverageRules.RulePlan change = PayerCoverageRules.plan(sameTarget, from, body.isReplaceScheduled());

        // Close first, then insert: the partial unique indexes (one live rule per target and start, one open-ended
        // rule per target) hold at every statement.
        List<PayerPlanRule> touched = new ArrayList<>();
        for (PayerPlanRule replaced : change.toReplace()) {
            replaced.setEffectiveTo(replaced.getEffectiveFrom().minusDays(1));
            touched.add(replaced);
        }
        change.predecessor().ifPresent(previous -> {
            previous.setEffectiveTo(from.minusDays(1));
            touched.add(previous);
        });
        ruleRepository.saveAll(touched);
        ruleRepository.flush();
        PayerPlanRule rule = ruleRepository.saveAndFlush(PayerPlanRule.builder()
            .hospitalId(actor.hospitalId())
            .planId(plan.getId())
            .category(body.getCategory())
            .priceListItemId(body.getPriceListItemId())
            .coveragePercent(percent)
            .excluded(body.isExcluded())
            .perActCeiling(perAct)
            .effectiveFrom(from)
            .createdBy(actor.userId())
            .build());
        plan.setUpdatedBy(actor.userId());
        planRepository.save(plan);
        audit(AuditEventType.PAYER_PLAN_CHANGED, actor,
            "Rule " + rule.getId() + " added to payer plan " + plan.getId() + ", effective " + from,
            AUDIT_ENTITY_PLAN, plan.getId());
        return toDtos(List.of(payer)).get(0);
    }

    /**
     * The rule stops applying from today (a rule that has not started yet is withdrawn: it never applies). Who ended
     * it, when and why stay with it.
     */
    @Transactional
    public PayerDTO endRule(UUID payerId, UUID planId, UUID ruleId, PayerReasonRequestDTO request) {
        Maintainer actor = requireMaintainer();
        Payer payer = lockPayer(payerId, actor);
        requireNotWithdrawn(payer);
        PayerPlan plan = planAtPayer(planId, payer, actor);
        PayerPlanRule rule = ruleRepository.findByIdAndPlanIdAndHospitalId(ruleId, plan.getId(), actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(RULE_NOT_FOUND_KEY, ruleId));
        LocalDate today = today();
        // A rule already ended keeps its first reason: a second call is refused, never overwrites it.
        if (rule.getEndedAt() != null || rule.isCancelled()
            || (rule.getEffectiveTo() != null && rule.getEffectiveTo().isBefore(today))) {
            throw new BusinessException("billing.payerRule.alreadyEnded");
        }
        String reason = requiredReason(request, "billing.payerRule.endReasonRequired");
        rule.setEffectiveTo(rule.getEffectiveFrom().isBefore(today)
            ? today.minusDays(1)
            : rule.getEffectiveFrom().minusDays(1));
        rule.setEndedAt(LocalDateTime.now(clock));
        rule.setEndedBy(actor.userId());
        rule.setEndReason(reason);
        ruleRepository.saveAndFlush(rule);
        plan.setUpdatedBy(actor.userId());
        planRepository.save(plan);
        audit(AuditEventType.PAYER_PLAN_CHANGED, actor,
            "Rule " + rule.getId() + " of payer plan " + plan.getId() + " ended", AUDIT_ENTITY_PLAN, plan.getId());
        return toDtos(List.of(payer)).get(0);
    }

    // ───────────────────────────── coverage link ─────────────────────────────

    /**
     * The plan a patient's coverage at {@code hospitalId} may be linked to: a plan of THAT hospital (another
     * hospital's answers exactly as a missing one, 404), of an active payer, not ended. Called by the patient
     * insurance service after it has checked the caller and the coverage row.
     */
    @Transactional(readOnly = true)
    public PayerPlan requireLinkablePlan(UUID planId, UUID hospitalId) {
        PayerPlan plan = planRepository.findByIdAndHospitalId(planId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(PLAN_NOT_FOUND_KEY, planId));
        boolean payerActive = payerRepository.findByIdAndHospitalId(plan.getPayerId(), hospitalId)
            .map(Payer::isActive).orElse(false);
        if (!payerActive || plan.endedBefore(today())) {
            throw new FieldBusinessException(FIELD_PAYER_PLAN, "billing.payerPlan.notLinkable");
        }
        return plan;
    }

    /**
     * The audit row of a patient's coverage linked to a plan ({@code planId}) or unlinked (null): ids only, written
     * after the commit of the caller's transaction.
     */
    public void auditCoverageLink(UUID insuranceId, UUID planId, UUID hospitalId, UUID actorUserId) {
        String description = planId == null
            ? "Patient insurance " + insuranceId + " unlinked from its payer plan"
            : "Patient insurance " + insuranceId + " linked to payer plan " + planId;
        audit(AuditEventType.PATIENT_COVERAGE_PLAN_LINKED, new Maintainer(actorUserId, hospitalId), description,
            AUDIT_ENTITY_COVERAGE, insuranceId);
    }

    /** The label of one plan a coverage row names (null when it names none). */
    @Transactional(readOnly = true)
    public PlanLabel labelOf(UUID planId) {
        return planId == null ? null : readLabels(Set.of(planId)).get(planId);
    }

    /** What a coverage row shows for its plan: the payer's name and code, the plan's name and code. */
    public record PlanLabel(UUID planId, String planCode, String planName, String payerCode, String payerName) {
    }

    /**
     * The labels of plans named by coverage rows the caller has already been allowed to read. A plan is always of its
     * coverage row's hospital (the link checks it), so the ids alone are enough here.
     */
    @Transactional(readOnly = true)
    public Map<UUID, PlanLabel> labelsOf(Collection<UUID> planIds) {
        return readLabels(planIds);
    }

    private Map<UUID, PlanLabel> readLabels(Collection<UUID> planIds) {
        Set<UUID> ids = new HashSet<>(planIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<PayerPlan> plans = planRepository.findAllById(ids);
        Map<UUID, Payer> payers = new HashMap<>();
        payerRepository.findAllById(plans.stream().map(PayerPlan::getPayerId).collect(Collectors.toSet()))
            .forEach(payer -> payers.put(payer.getId(), payer));
        Map<UUID, PlanLabel> labels = new HashMap<>();
        for (PayerPlan plan : plans) {
            Payer payer = payers.get(plan.getPayerId());
            labels.put(plan.getId(), new PlanLabel(plan.getId(), plan.getCode(), plan.getName(),
                payer == null ? null : payer.getCode(), payer == null ? null : payer.getName()));
        }
        return labels;
    }

    // ───────────────────────────── access ─────────────────────────────

    /** Who wrote, at which hospital. */
    record Maintainer(UUID userId, UUID hospitalId) {
    }

    /**
     * The acting hospital (pinned, never global view) when the caller holds HOSPITAL_ADMIN or ACCOUNTANT there.
     * Checked before any lookup.
     */
    Maintainer requireMaintainer() {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canMaintainPayers(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.payer.maintainersOnly"));
        }
        return new Maintainer(userId, hospitalId);
    }

    /**
     * The hospital whose catalogue the caller reads: the acting one, where the caller holds a reading role; null for
     * the verified super-admin's global view (read-only).
     */
    UUID requireReader() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        boolean verifiedSuperAdmin = actingScopeResolver.isVerifiedSuperAdmin();
        if (hospitalId == null) {
            if (!verifiedSuperAdmin) {
                throw new AccessDeniedException("Access denied");
            }
            return null;
        }
        if (verifiedSuperAdmin) {
            return hospitalId;
        }
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canReadPayers(userId, hospitalId)) {
            throw new AccessDeniedException("Access denied");
        }
        return hospitalId;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private record Terms(Integer percent, BigDecimal perInvoice, BigDecimal annual) {
    }

    private static Terms termsOf(BigDecimal percent, BigDecimal perInvoice,
                                 BigDecimal annual) {
        return new Terms(PayerCoverageRules.percentOf(percent, FIELD_DEFAULT_PERCENT),
            PayerCoverageRules.ceilingOf(perInvoice, FIELD_INVOICE_CEILING),
            PayerCoverageRules.ceilingOf(annual, FIELD_ANNUAL_CEILING));
    }

    private Payer lockPayer(UUID payerId, Maintainer actor) {
        return payerRepository.lockByIdAtHospital(payerId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(PAYER_NOT_FOUND_KEY, payerId));
    }

    private PayerPlan planAtPayer(UUID planId, Payer payer, Maintainer actor) {
        return planRepository.findByIdAndPayerIdAndHospitalId(planId, payer.getId(), actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(PLAN_NOT_FOUND_KEY, planId));
    }

    /** A deactivated payer is closed: it takes no edit, no new plan and no new rule. */
    private static void requireNotWithdrawn(Payer payer) {
        if (!payer.isActive()) {
            throw new BusinessException("billing.payer.inactive");
        }
    }

    private static String normalisedCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimmed(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static void requireCodeFormat(String code) {
        if (code.length() > CODE_MAX || !CODE_FORMAT.matcher(code).matches()) {
            throw new FieldBusinessException(FIELD_CODE, "billing.payer.codeFormat", CODE_MAX);
        }
    }

    private static void requireNameLength(String name) {
        if (name.length() > NAME_MAX) {
            throw new FieldBusinessException(FIELD_NAME, "billing.payer.nameTooLong", NAME_MAX);
        }
    }

    private static String requiredReason(PayerReasonRequestDTO request, String requiredKey) {
        String reason = request == null || request.getReason() == null ? "" : request.getReason().trim();
        RequiredFields.check().require(!reason.isEmpty(), FIELD_REASON, requiredKey).orThrow();
        if (reason.length() > REASON_MAX) {
            throw new FieldBusinessException(FIELD_REASON, "billing.payer.reasonTooLong", REASON_MAX);
        }
        return reason;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(businessZone));
    }

    private void audit(AuditEventType type, Maintainer actor, String description, String entity, UUID resourceId) {
        String resource = String.valueOf(resourceId);
        UUID userId = actor.userId();
        UUID hospitalId = actor.hospitalId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .userId(userId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(entity)
                    .resourceId(resource)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Payer catalogue audit {} not written for {} {}: {}", type, entity, resource,
                    ex.getClass().getSimpleName());
            }
        });
    }

    // ───────────────────────────── mapping ─────────────────────────────

    /** Payers with their plans and rules, read in a handful of queries (chunked IN lists for the global view). */
    private List<PayerDTO> toDtos(List<Payer> payers) {
        if (payers.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PayerPlan>> plansByPayer = new HashMap<>();
        List<PayerPlan> plans = new ArrayList<>();
        for (List<UUID> chunk : PriceListService.chunksOf(payers.stream().map(Payer::getId).toList())) {
            plans.addAll(planRepository.findByPayerIdInOrderByCodeAsc(chunk));
        }
        plans.forEach(plan -> plansByPayer.computeIfAbsent(plan.getPayerId(), id -> new ArrayList<>()).add(plan));
        Map<UUID, List<PayerPlanRule>> rulesByPlan = new HashMap<>();
        List<PayerPlanRule> rules = new ArrayList<>();
        for (List<UUID> chunk : PriceListService.chunksOf(plans.stream().map(PayerPlan::getId).toList())) {
            rules.addAll(ruleRepository.findByPlanIds(chunk));
        }
        rules.forEach(rule -> rulesByPlan.computeIfAbsent(rule.getPlanId(), id -> new ArrayList<>()).add(rule));
        Map<UUID, PriceListItem> items = new HashMap<>();
        List<UUID> itemIds = rules.stream().map(PayerPlanRule::getPriceListItemId).filter(Objects::nonNull)
            .distinct().toList();
        for (List<UUID> chunk : PriceListService.chunksOf(itemIds)) {
            priceListItemRepository.findAllById(chunk).forEach(item -> items.put(item.getId(), item));
        }
        Names names = names(payers, plans, rules);
        LocalDate today = today();
        return payers.stream().map(payer -> PayerDTO.builder()
            .id(payer.getId())
            .hospitalId(payer.getHospitalId())
            .code(payer.getCode())
            .name(payer.getName())
            .payerType(payer.getPayerType())
            .active(payer.isActive())
            .deactivatedAt(payer.getDeactivatedAt())
            .deactivatedByName(names.of(payer.getDeactivatedBy()))
            .deactivationReason(payer.getDeactivationReason())
            .createdAt(payer.getCreatedAt())
            .createdByName(names.of(payer.getCreatedBy()))
            .updatedAt(payer.getUpdatedAt())
            .updatedByName(names.of(payer.getUpdatedBy()))
            .plans(plansByPayer.getOrDefault(payer.getId(), List.of()).stream()
                .map(plan -> toDto(plan, payer, rulesByPlan.getOrDefault(plan.getId(), List.of()), items, names, today))
                .toList())
            .build()).toList();
    }

    private static PayerPlanDTO toDto(PayerPlan plan, Payer payer, List<PayerPlanRule> rules,
                                      Map<UUID, PriceListItem> items, Names names, LocalDate today) {
        return PayerPlanDTO.builder()
            .id(plan.getId())
            .payerId(plan.getPayerId())
            .code(plan.getCode())
            .name(plan.getName())
            .defaultCoveragePercent(plan.getDefaultCoveragePercent())
            .perInvoiceCeiling(plan.getPerInvoiceCeiling())
            .annualCeilingPerBeneficiary(plan.getAnnualCeilingPerBeneficiary())
            .effectiveFrom(plan.getEffectiveFrom())
            .effectiveTo(plan.isWithdrawn() ? null : plan.getEffectiveTo())
            .status(PayerCoverageRules.statusOf(plan, today))
            .linkable(payer.isActive() && !plan.endedBefore(today))
            .createdAt(plan.getCreatedAt())
            .createdByName(names.of(plan.getCreatedBy()))
            .updatedAt(plan.getUpdatedAt())
            .updatedByName(names.of(plan.getUpdatedBy()))
            .rules(rules.stream().map(rule -> toDto(rule, items, names, today)).toList())
            .build();
    }

    private static PayerPlanRuleDTO toDto(PayerPlanRule rule, Map<UUID, PriceListItem> items, Names names,
                                          LocalDate today) {
        PriceListItem item = rule.getPriceListItemId() == null ? null : items.get(rule.getPriceListItemId());
        return PayerPlanRuleDTO.builder()
            .id(rule.getId())
            .planId(rule.getPlanId())
            .category(rule.getCategory())
            .priceListItemId(rule.getPriceListItemId())
            .priceListItemCode(item == null ? null : item.getCode())
            .priceListItemLabel(item == null ? null : item.getLabel())
            .coveragePercent(rule.getCoveragePercent())
            .excluded(rule.isExcluded())
            .perActCeiling(rule.getPerActCeiling())
            .effectiveFrom(rule.getEffectiveFrom())
            .effectiveTo(rule.isCancelled() ? null : rule.getEffectiveTo())
            .status(PayerCoverageRules.statusOf(rule, today))
            .createdAt(rule.getCreatedAt())
            .createdByName(names.of(rule.getCreatedBy()))
            .endedAt(rule.getEndedAt())
            .endedByName(names.of(rule.getEndedBy()))
            .endReason(rule.getEndReason())
            .build();
    }

    /** Display names of the people on these rows, read in one query per chunk. */
    private record Names(Map<UUID, String> byId) {
        String of(UUID id) {
            return id == null ? null : byId.get(id);
        }
    }

    private Names names(Collection<Payer> payers, Collection<PayerPlan> plans, Collection<PayerPlanRule> rules) {
        Set<UUID> ids = new HashSet<>();
        payers.forEach(p -> {
            ids.add(p.getCreatedBy());
            ids.add(p.getUpdatedBy());
            ids.add(p.getDeactivatedBy());
        });
        plans.forEach(p -> {
            ids.add(p.getCreatedBy());
            ids.add(p.getUpdatedBy());
        });
        rules.forEach(r -> {
            ids.add(r.getCreatedBy());
            ids.add(r.getEndedBy());
        });
        ids.remove(null);
        if (ids.isEmpty()) {
            return new Names(Map.of());
        }
        Map<UUID, String> byId = new HashMap<>();
        for (List<UUID> chunk : PriceListService.chunksOf(new ArrayList<>(ids))) {
            userRepository.findAllById(chunk).forEach(user -> byId.putIfAbsent(user.getId(), displayName(user)));
        }
        return new Names(byId);
    }

    private static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
