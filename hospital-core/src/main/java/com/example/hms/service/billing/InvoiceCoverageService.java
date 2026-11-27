package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PayerShareStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.InvoicePayerShare;
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
import com.example.hms.security.context.ImpersonationLimits;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.CardNumbers;
import com.example.hms.utility.InvoiceBalance;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Billing-money plan PR 7 (V306, plan section 3.4): a payer's coverage applied to the invoice.
 *
 * <ul>
 *   <li><b>When the invoice is issued</b> ({@link #applyAtIssue}), the patient's coverage at the invoice's hospital
 *       that names a payer plan and covers the service date (the invoice date, the checkout day: D-4) is taken,
 *       primary first. The plan must be offered that day and have terms in force that day; otherwise there is no
 *       coverage and the patient owes the whole bill. The share is computed by {@link CoverageCalculator} under a row
 *       lock on the coverage, so two invoices issued at once cannot both use what is left of its annual ceiling.</li>
 *   <li><b>When a credit note changes a line</b> (made or reversed: {@link #recomputeAfterLineChange}), the live share
 *       is computed again on the lines less their credit notes. A changed amount cancels the old share and writes a
 *       new one: an amount is never rewritten. A share already claimed or paid is not changed; the credit note is
 *       refused instead.</li>
 *   <li><b>When the invoice is cancelled</b> ({@link #cancelWithInvoice}), its open share is cancelled, which frees
 *       the annual ceiling. A share already claimed or paid keeps the invoice from being cancelled.</li>
 *   <li><b>The payer's refusal before any claim</b> ({@link #reject}): a REJECTED share, with the
 *       payer's reason, moves back to the patient: the balance rises and the history keeps the original share.</li>
 * </ul>
 *
 * <p>Every write is at the acting hospital, audited after commit with ids only, and refused to a support session
 * impersonating a user ({@link ImpersonationLimits}). The platform super-admin reads but never decides.
 */
@Slf4j
@Service
public class InvoiceCoverageService {

    private static final String INVOICE_NOT_FOUND = "billinginvoice.notfound";
    private static final String SHARE_NOT_FOUND = "billing.payerShare.notFound";
    private static final String AUDIT_ENTITY = "BILLING_INVOICE";
    private static final String FIELD_REASON = "reason";
    private static final int REASON_MAX = 500;
    private static final Set<PayerShareStatus> LIVE = EnumSet.of(PayerShareStatus.OPEN, PayerShareStatus.CLAIMED,
        PayerShareStatus.PARTIALLY_PAID, PayerShareStatus.PAID, PayerShareStatus.WRITTEN_OFF);

    private final InvoicePayerShareRepository shareRepository;
    private final BillingInvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final InvoiceAdjustmentRepository adjustmentRepository;
    private final PatientInsuranceRepository insuranceRepository;
    private final PayerRepository payerRepository;
    private final PayerPlanRepository planRepository;
    private final PayerPlanTermsRepository termsRepository;
    private final PayerPlanRuleRepository ruleRepository;
    private final PriceListItemRepository priceListItemRepository;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per table the coverage reads; a facade would only hide them
    public InvoiceCoverageService(InvoicePayerShareRepository shareRepository,
                                  BillingInvoiceRepository invoiceRepository,
                                  InvoiceItemRepository itemRepository,
                                  InvoiceAdjustmentRepository adjustmentRepository,
                                  PatientInsuranceRepository insuranceRepository,
                                  PayerRepository payerRepository,
                                  PayerPlanRepository planRepository,
                                  PayerPlanTermsRepository termsRepository,
                                  PayerPlanRuleRepository ruleRepository,
                                  PriceListItemRepository priceListItemRepository,
                                  RoleValidator roleValidator,
                                  ActingScopeResolver actingScopeResolver,
                                  AuditEventLogService auditEventLogService,
                                  Clock clock) {
        this.shareRepository = shareRepository;
        this.invoiceRepository = invoiceRepository;
        this.itemRepository = itemRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.insuranceRepository = insuranceRepository;
        this.payerRepository = payerRepository;
        this.planRepository = planRepository;
        this.termsRepository = termsRepository;
        this.ruleRepository = ruleRepository;
        this.priceListItemRepository = priceListItemRepository;
        this.roleValidator = roleValidator;
        this.actingScopeResolver = actingScopeResolver;
        this.auditEventLogService = auditEventLogService;
        this.clock = clock;
    }

    /** The coverage that applies, its plan and the plan's terms and rules on the service date. */
    record Coverage(PatientInsurance insurance, PayerPlan plan, PayerPlanTerms terms, List<PayerPlanRule> rules) {
    }

    /* ------------------------------------------------------------------ issue */

    /**
     * Applies the patient's coverage to an invoice being issued (the caller holds the invoice's row lock and moves it
     * from DRAFT to SENT). Nothing happens for a check-in copay, an invoice with no lines, or a patient with no
     * coverage that applies on the service date. Returns the payers' share now on the invoice.
     */
    public BigDecimal applyAtIssue(BillingInvoice invoice, UUID actorId) {
        if (invoice.isCheckInCopay() || invoice.getPatient() == null || invoice.getHospital() == null
                || invoice.getId() == null) {
            return BigDecimal.ZERO;
        }
        UUID hospitalId = invoice.getHospital().getId();
        if (!shareRepository.findByInvoiceIdAndHospitalIdAndStatusIn(invoice.getId(), hospitalId, LIVE).isEmpty()) {
            // Issued once already (a retried request): the share stands as it was computed.
            return InvoiceBalance.payerShareOf(invoice);
        }
        List<InvoiceItem> lines = linesOf(invoice);
        if (lines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        LocalDate day = serviceDay(invoice);
        Optional<Coverage> coverage = coverageOn(invoice.getPatient().getId(), hospitalId, day);
        if (coverage.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Coverage applied = coverage.get();
        // The annual ceiling is read under the coverage's row lock (plan 3.4).
        insuranceRepository.lockById(applied.insurance().getId());
        CoverageCalculator.Result result = compute(invoice, hospitalId, lines, applied, day);
        if (result.total().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        InvoicePayerShare share = writeShare(invoice, hospitalId, applied.insurance().getId(), applied.plan().getId(),
            result.total(), day, actorId);
        writeShareLines(share, result);
        applyToLines(lines, result);
        invoice.setPayerShareTotal(result.total());
        audit(AuditEventType.PAYER_SHARE_APPLIED, actorId, invoice, share.getId(), "Payer share applied");
        return result.total();
    }

    /* ---------------------------------------------------------- preview */

    /**
     * QA D2437: what the patient's coverage would pay on a DRAFT if it were issued now, computed exactly as
     * {@link #applyAtIssue} would (same coverage, plan, terms, rules, ceiling used so far), but read-only: no lock,
     * no share written, nothing on the lines. Empty for anything but a draft with lines and an applicable coverage
     * that pays something. An estimate: the ceiling can be used by another invoice before this one is issued.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<CoverageCalculator.Result> previewForDraft(BillingInvoice invoice) {
        if (invoice == null || invoice.getStatus() != com.example.hms.enums.InvoiceStatus.DRAFT
                || invoice.isCheckInCopay() || invoice.getPatient() == null || invoice.getHospital() == null
                || invoice.getId() == null) {
            return Optional.empty();
        }
        UUID hospitalId = invoice.getHospital().getId();
        List<InvoiceItem> lines = linesOf(invoice);
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        LocalDate day = serviceDay(invoice);
        return coverageOn(invoice.getPatient().getId(), hospitalId, day)
            .map(coverage -> compute(invoice, hospitalId, lines, coverage, day))
            .filter(result -> result.total().signum() > 0);
    }

    /* ------------------------------------------------------- credit notes */

    /**
     * A credit note on a line was made or reversed (the caller holds the invoice's row lock and has written the
     * adjustment and the new total). The live share is computed again on the same coverage and plan; returns the
     * payers' share now on the invoice. An invoice with no live share keeps none.
     *
     * @throws ConflictException when the share would change but the payer has already been sent it or paid it
     */
    public BigDecimal recomputeAfterLineChange(BillingInvoice invoice, UUID actorId) {
        UUID hospitalId = invoice.getHospital().getId();
        List<InvoicePayerShare> live = shareRepository.findByInvoiceIdAndHospitalIdAndStatusIn(invoice.getId(),
            hospitalId, LIVE);
        if (live.isEmpty()) {
            return InvoiceBalance.payerShareOf(invoice);
        }
        InvoicePayerShare current = live.get(0);
        LocalDate day = serviceDay(invoice);
        insuranceRepository.lockById(current.getPatientInsuranceId());
        List<InvoiceItem> lines = linesOf(invoice);
        Coverage coverage = sameCoverage(current, hospitalId, day);
        CoverageCalculator.Result result = coverage == null
            ? new CoverageCalculator.Result(List.of(), BigDecimal.ZERO.setScale(2))
            : compute(invoice, hospitalId, lines, coverage, day);
        if (current.getStatus() != PayerShareStatus.OPEN) {
            // Insurer claims plan PR 2: a share on a bordereau already sent is frozen with it, line by line. A
            // credit note that leaves each line's payer part as it is goes through; one that would move the total
            // or the split is refused.
            if (result.total().compareTo(current.getAmount()) == 0 && sameSplit(current, result)) {
                return InvoiceBalance.payerShareOf(invoice);
            }
            throw new ConflictException(MessageUtil.resolve("billing.payerShare.lockedOnceClaimed"));
        }
        if (result.total().compareTo(current.getAmount()) == 0) {
            // Review of #1353 (F1): the total can stand while the split moves (a ceiling filled in line order), so
            // the share's own rows follow the lines; a draft bordereau holding the share follows them too.
            if (resplitShareLines(current, result)) {
                followOnDrafts(current.getId(), current, hospitalId);
            }
            applyToLines(lines, result);
            return InvoiceBalance.payerShareOf(invoice);
        }
        LocalDateTime at = LocalDateTime.now(clock);
        claimed(shareRepository.decide(current.getId(), hospitalId, EnumSet.of(PayerShareStatus.OPEN),
            PayerShareStatus.CANCELLED, actorId, at, null));
        current.setStatus(PayerShareStatus.CANCELLED);
        audit(AuditEventType.PAYER_SHARE_CANCELLED, actorId, invoice, current.getId(),
            "Payer share replaced after a credit note");
        InvoicePayerShare replacement = null;
        if (result.total().signum() > 0) {
            replacement = writeShare(invoice, hospitalId, current.getPatientInsuranceId(),
                current.getPayerPlanId(), result.total(), day, actorId);
            writeShareLines(replacement, result);
            audit(AuditEventType.PAYER_SHARE_APPLIED, actorId, invoice, replacement.getId(),
                "Payer share applied after a credit note");
        }
        // Insurer claims plan PR 2: a draft bordereau holding the old share now holds the new one, or drops it.
        followOnDrafts(current.getId(), replacement, hospitalId);
        applyToLines(lines, result);
        invoice.setPayerShareTotal(result.total());
        return result.total();
    }

    /* ---------------------------------------------------------- cancel */

    /**
     * The invoice is being cancelled (the caller holds its row lock): its open share is cancelled, which frees the
     * annual ceiling.
     *
     * @throws ConflictException when the payer has already been sent the share or paid it
     */
    public void cancelWithInvoice(BillingInvoice invoice, UUID actorId) {
        if (invoice.getHospital() == null || invoice.getId() == null) {
            return;
        }
        UUID hospitalId = invoice.getHospital().getId();
        List<InvoicePayerShare> live = shareRepository.findByInvoiceIdAndHospitalIdAndStatusIn(invoice.getId(),
            hospitalId, LIVE);
        if (live.stream().anyMatch(share -> share.getStatus() != PayerShareStatus.OPEN)) {
            throw new ConflictException(MessageUtil.resolve("billing.payerShare.cancelOnceClaimed"));
        }
        LocalDateTime at = LocalDateTime.now(clock);
        for (InvoicePayerShare share : live) {
            claimed(shareRepository.decide(share.getId(), hospitalId, EnumSet.of(PayerShareStatus.OPEN),
                PayerShareStatus.CANCELLED, actorId, at, null));
            share.setStatus(PayerShareStatus.CANCELLED);
            followOnDrafts(share.getId(), null, hospitalId);
            audit(AuditEventType.PAYER_SHARE_CANCELLED, actorId, invoice, share.getId(),
                "Payer share cancelled with its invoice");
        }
        if (!live.isEmpty()) {
            linesOf(invoice).forEach(line -> line.setPayerShareAmount(BigDecimal.ZERO));
            invoice.setPayerShareTotal(BigDecimal.ZERO);
        }
    }

    /* ------------------------------------------------------- decisions */

    /**
     * The payer refused the whole share before any claim was sent (the cover was wrong, the rights not valid): OPEN
     * to REJECTED, with its reason. The amount moves back to the patient (the invoice's balance rises by it); the
     * row keeps the original amount. Insurer claims plan PR 3: a share is claimed by sending its bordereau and paid
     * by an insurer payment with an amount, a date and a reference, so the "claimed" and "paid" buttons are gone;
     * a share already on a sent bordereau is refused line by line there, with the class of the payer's reason.
     */
    @Transactional
    public List<InvoicePayerShareDTO> reject(UUID invoiceId, UUID shareId, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) {
            throw new FieldBusinessException(FIELD_REASON, "billing.payerShare.reasonRequired");
        }
        if (why.length() > REASON_MAX) {
            throw new FieldBusinessException(FIELD_REASON, "billing.payerShare.reasonTooLong");
        }
        CardNumbers.requireNone(why, FIELD_REASON);
        ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "payer share");
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canDecidePayerShare(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.payerShare.decidersOnly"));
        }
        BillingInvoice invoice = invoiceRepository.findLockedById(invoiceId)
            .filter(found -> found.getHospital() != null && hospitalId.equals(found.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_NOT_FOUND, invoiceId));
        InvoicePayerShare share = shareRepository.findByIdAndInvoiceIdAndHospitalId(shareId, invoiceId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(SHARE_NOT_FOUND));
        if (share.getStatus() != PayerShareStatus.OPEN) {
            throw new ConflictException(MessageUtil.resolve("billing.payerShare.notDecidable",
                MessageUtil.resolve("billing.payerShare.status." + share.getStatus().name())));
        }
        LocalDateTime at = LocalDateTime.now(clock);
        claimed(shareRepository.rejectWhole(share.getId(), hospitalId, userId, at, why));
        share.setStatus(PayerShareStatus.REJECTED);
        share.setRejectedAmount(share.getAmount());
        share.setDecidedBy(userId);
        share.setDecidedAt(at);
        share.setRejectionReason(why);
        // A draft bordereau no longer claims a share the payer will not pay.
        followOnDrafts(share.getId(), null, hospitalId);
        BigDecimal left = InvoiceBalance.payerShareOf(invoice).subtract(share.getAmount()).max(BigDecimal.ZERO);
        invoice.setPayerShareTotal(left);
        if (left.signum() == 0) {
            linesOf(invoice).forEach(line -> line.setPayerShareAmount(BigDecimal.ZERO));
        }
        // The share is the patient's again: the invoice is no longer settled by what the patient paid.
        invoice.setStatus(InvoiceBalance.settledStatusOf(invoice));
        invoiceRepository.save(invoice);
        audit(AuditEventType.PAYER_SHARE_DECIDED, userId, invoice, share.getId(), "Payer share rejected");
        return readShares(invoice);
    }

    /* ---------------------------------------------------------- reads */

    /** The invoice's shares, oldest first, with the payer and plan names (staff read; scope checked by the caller). */
    @Transactional(readOnly = true)
    public List<InvoicePayerShareDTO> sharesOf(BillingInvoice invoice) {
        return readShares(invoice);
    }

    /** The body of {@link #sharesOf}, called from this class's own transactions (Sonar S6809). */
    private List<InvoicePayerShareDTO> readShares(BillingInvoice invoice) {
        if (invoice == null || invoice.getId() == null || invoice.getHospital() == null) {
            return List.of();
        }
        UUID hospitalId = invoice.getHospital().getId();
        List<InvoicePayerShare> shares = shareRepository.findByInvoiceIdAndHospitalIdOrderByCreatedAtAsc(
            invoice.getId(), hospitalId);
        if (shares.isEmpty()) {
            return List.of();
        }
        Map<UUID, PayerPlan> plans = new HashMap<>();
        Map<UUID, String> payerNames = new HashMap<>();
        Map<UUID, Optional<PatientInsurance>> coverages = new HashMap<>();
        List<InvoicePayerShareDTO> out = new ArrayList<>();
        for (InvoicePayerShare share : shares) {
            PayerPlan plan = plans.computeIfAbsent(share.getPayerPlanId(),
                id -> planRepository.findByIdAndHospitalId(id, hospitalId).orElse(null));
            String payerName = plan == null ? null : payerNames.computeIfAbsent(plan.getPayerId(),
                id -> payerRepository.findByIdAndHospitalId(id, hospitalId).map(Payer::getName).orElse(null));
            // Insurer claims plan PR 1 (QA D2712): the policy and subscriber of the coverage the share was computed
            // under, read at the invoice's own hospital only.
            Optional<PatientInsurance> coverage = coverages.computeIfAbsent(share.getPatientInsuranceId(),
                id -> insuranceRepository.findByIdAndAssignment_Hospital_Id(id, hospitalId));
            out.add(new InvoicePayerShareDTO(share.getId(), payerName, plan != null ? plan.getName() : null,
                share.getAmount(), share.getCoverageYear() != null ? share.getCoverageYear() : 0,
                share.getStatus().name(), share.getRejectionReason(), share.getDecidedAt(), share.getCreatedAt(),
                coverage.map(PatientInsurance::getPolicyNumber).orElse(null),
                coverage.map(PatientInsurance::getSubscriberName).orElse(null),
                coverage.map(PatientInsurance::getSubscriberRelationship).orElse(null),
                share.getPaidAmount(), share.getRejectedAmount(), share.getWrittenOffAmount()));
        }
        return List.copyOf(out);
    }

    /** Insurer claims plan PR 1 (G2): the largest page of the « Parts assureurs » list. */
    public static final int SHARE_PAGE_MAX = 100;
    private static final int SHARE_PAGE_DEFAULT = 25;
    private static final LocalDate EARLIEST_SERVICE_DAY = LocalDate.of(1900, 1, 1);
    private static final LocalDate LATEST_SERVICE_DAY = LocalDate.of(9999, 12, 31);
    /** Stands for a filter left out: never compared, since the matching {@code any…} flag is then true. */
    private static final UUID NO_ID = new UUID(0L, 0L);

    /** The filters of the « Parts assureurs » list; each null one is left out. Page is zero-based. */
    public record ShareFilter(Collection<PayerShareStatus> statuses, UUID payerId, UUID planId, UUID patientId,
                              LocalDate from, LocalDate to, Integer page, Integer size) {
    }

    /**
     * QA D2706 (r70), insurer claims plan PR 1 (G1, G2): one page of the « Parts assureurs » list of the acting
     * hospital: the shares OPEN or CLAIMED (or the statuses asked for), filtered by payer, plan, patient and service
     * date (the invoice date, D-4), oldest service date first, with the total of every page. Each row carries what a
     * claim needs: the payer and its kind, the plan, the policy number and subscriber of the coverage, and the acts
     * the payer pays part of, each with its act code and pre-authorisation number. Read by the roles that decide a
     * share (hospital admin, accountant, billing, claims reviewer) at that hospital; a verified super-admin pinned to
     * it reads too. Another hospital's share, invoice, plan, coverage or line is never read.
     */
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.billing.PayerSharePageDTO searchAtHospital(ShareFilter filter) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        boolean reader = actingScopeResolver.isVerifiedSuperAdmin()
            || (userId != null && roleValidator.canDecidePayerShare(userId, hospitalId));
        if (!reader) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.payerShare.decidersOnly"));
        }
        ShareFilter f = filter != null ? filter : new ShareFilter(null, null, null, null, null, null, null, null);
        Collection<PayerShareStatus> wanted = f.statuses() == null || f.statuses().isEmpty()
            ? EnumSet.of(PayerShareStatus.OPEN, PayerShareStatus.CLAIMED) : f.statuses();
        LocalDate from = f.from() != null ? f.from() : EARLIEST_SERVICE_DAY;
        LocalDate to = f.to() != null ? f.to() : LATEST_SERVICE_DAY;
        if (to.isBefore(from)) {
            throw new FieldBusinessException("toDate", "billing.payerShare.periodReversed");
        }
        int size = f.size() == null || f.size() <= 0 ? SHARE_PAGE_DEFAULT : Math.min(f.size(), SHARE_PAGE_MAX);
        int page = f.page() == null || f.page() < 0 ? 0 : f.page();
        UUID payerId = f.payerId() != null ? f.payerId() : NO_ID;
        UUID planId = f.planId() != null ? f.planId() : NO_ID;
        UUID patientId = f.patientId() != null ? f.patientId() : NO_ID;
        org.springframework.data.domain.Page<InvoicePayerShare> found = shareRepository.searchAtHospital(hospitalId,
            wanted, from, to, f.payerId() == null, payerId, f.planId() == null, planId, f.patientId() == null,
            patientId, org.springframework.data.domain.PageRequest.of(page, size));
        BigDecimal total = nonNull(shareRepository.sumSearchAtHospital(hospitalId, wanted, from, to,
            f.payerId() == null, payerId, f.planId() == null, planId, f.patientId() == null, patientId));
        ShareNames names = new ShareNames(hospitalId, found.getContent());
        List<com.example.hms.payload.dto.billing.OpenPayerShareDTO> rows = new ArrayList<>();
        for (InvoicePayerShare share : found.getContent()) {
            names.invoice(share.getInvoiceId()).ifPresent(invoice -> rows.add(openShare(share, invoice, names)));
        }
        return new com.example.hms.payload.dto.billing.PayerSharePageDTO(List.copyOf(rows), page, size,
            found.getTotalElements(), found.getTotalPages(), total);
    }

    /**
     * The invoices, plans, payers, coverages, lines and act codes one page of « Parts assureurs » reads: each read
     * once, the page's lines in one query, everything at one hospital.
     */
    private final class ShareNames {
        private final UUID hospitalId;
        private final Map<UUID, Optional<BillingInvoice>> invoices = new HashMap<>();
        private final Map<UUID, Optional<PayerPlan>> plans = new HashMap<>();
        private final Map<UUID, Optional<Payer>> payers = new HashMap<>();
        private final Map<UUID, Optional<PatientInsurance>> coverages = new HashMap<>();
        private final Map<UUID, List<InvoiceItem>> linesByInvoice;
        private final Map<UUID, String> actCodes;
        /** Per share, what it claimed of each line (V383); empty for a share written before V383. */
        private final Map<UUID, Map<UUID, BigDecimal>> claimedByShare;
        /** Insurer claims plan PR 2: per share, the bordereau (not cancelled) it is on, and that bordereau's number. */
        private final Map<UUID, UUID> batchByShare = new HashMap<>();
        private final Map<UUID, String> batchNumbers = new HashMap<>();

        ShareNames(UUID hospitalId, List<InvoicePayerShare> shares) {
            this.hospitalId = hospitalId;
            Set<UUID> shareIds = shares.stream().map(InvoicePayerShare::getId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
            List<com.example.hms.model.InvoicePayerShareLine> claimed = shareLineRepository == null || shareIds.isEmpty()
                ? List.of() : shareLineRepository.findByShareIdInAndHospitalId(shareIds, hospitalId);
            this.claimedByShare = (claimed == null ? List.<com.example.hms.model.InvoicePayerShareLine>of() : claimed)
                .stream()
                .collect(Collectors.groupingBy(com.example.hms.model.InvoicePayerShareLine::getShareId,
                    Collectors.toMap(com.example.hms.model.InvoicePayerShareLine::getInvoiceItemId,
                        com.example.hms.model.InvoicePayerShareLine::getAmount, (a, b) -> a)));
            if (claimLineRepository != null && claimBatchRepository != null && !shareIds.isEmpty()) {
                claimLineRepository.findByShareIdInAndHospitalIdAndReleasedFalse(shareIds, hospitalId)
                    .forEach(line -> batchByShare.put(line.getShareId(), line.getBatchId()));
                Set<UUID> batchIds = new java.util.HashSet<>(batchByShare.values());
                if (!batchIds.isEmpty()) {
                    claimBatchRepository.findByIdInAndHospitalId(batchIds, hospitalId).stream()
                        .filter(batch -> batch.getNumber() != null)
                        .forEach(batch -> batchNumbers.put(batch.getId(), batch.getNumber()));
                }
            }
            Set<UUID> invoiceIds = shares.stream().map(InvoicePayerShare::getInvoiceId).collect(Collectors.toSet());
            List<InvoiceItem> lines = invoiceIds.isEmpty() ? List.of()
                : itemRepository.findByBillingInvoice_IdInAndBillingInvoice_Hospital_Id(invoiceIds, hospitalId);
            this.linesByInvoice = (lines == null ? List.<InvoiceItem>of() : lines).stream()
                .filter(line -> line.getBillingInvoice() != null)
                .collect(Collectors.groupingBy(line -> line.getBillingInvoice().getId()));
            Set<UUID> itemIds = linesByInvoice.values().stream().flatMap(List::stream)
                .map(InvoiceItem::getPriceListItemId).filter(Objects::nonNull).collect(Collectors.toSet());
            this.actCodes = itemIds.isEmpty() ? Map.of() : priceListItemRepository.findAllById(itemIds).stream()
                .filter(item -> hospitalId.equals(item.getHospitalId()) && item.getCode() != null)
                .collect(Collectors.toMap(PriceListItem::getId, PriceListItem::getCode, (a, b) -> a));
        }

        UUID batchId(UUID shareId) {
            return batchByShare.get(shareId);
        }

        String batchNumber(UUID shareId) {
            UUID batchId = batchByShare.get(shareId);
            return batchId != null ? batchNumbers.get(batchId) : null;
        }

        Optional<BillingInvoice> invoice(UUID id) {
            return invoices.computeIfAbsent(id, key -> invoiceRepository.findById(key)
                .filter(found -> found.getHospital() != null && hospitalId.equals(found.getHospital().getId())));
        }

        Optional<PayerPlan> plan(UUID id) {
            return plans.computeIfAbsent(id, key -> planRepository.findByIdAndHospitalId(key, hospitalId));
        }

        Optional<Payer> payer(PayerPlan plan) {
            return payers.computeIfAbsent(plan.getPayerId(),
                key -> payerRepository.findByIdAndHospitalId(key, hospitalId));
        }

        Optional<PatientInsurance> coverage(UUID id) {
            return coverages.computeIfAbsent(id,
                key -> insuranceRepository.findByIdAndAssignment_Hospital_Id(key, hospitalId));
        }

        /**
         * The lines the share claimed, in posting order, each with what this share claimed of it. Review of #1353:
         * read from the share's own rows (V383), so a share rejected or cancelled since (its lines' live amounts
         * cleared) still lists its acts; a share written before V383 has none and falls back to the live split.
         */
        List<com.example.hms.payload.dto.billing.PayerShareLineDTO> claimedLines(InvoicePayerShare share) {
            Map<UUID, BigDecimal> own = claimedByShare.get(share.getId());
            return linesByInvoice.getOrDefault(share.getInvoiceId(), List.of()).stream()
                .filter(line -> claimedOf(line, own).signum() > 0)
                .sorted(Comparator.comparing(InvoiceItem::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(line -> String.valueOf(line.getId())))
                .map(line -> claimedLine(line, claimedOf(line, own)))
                .toList();
        }

        private static BigDecimal claimedOf(InvoiceItem line, Map<UUID, BigDecimal> own) {
            if (own != null) {
                return own.getOrDefault(line.getId(), BigDecimal.ZERO);
            }
            return line.getPayerShareAmount() != null ? line.getPayerShareAmount() : BigDecimal.ZERO;
        }

        private com.example.hms.payload.dto.billing.PayerShareLineDTO claimedLine(InvoiceItem line, BigDecimal payer) {
            BigDecimal gross = line.getTotalPrice() != null ? line.getTotalPrice() : BigDecimal.ZERO;
            String category;
            if (line.getCoverageCategory() != null) {
                category = line.getCoverageCategory().name();
            } else {
                category = line.getItemCategory() != null ? line.getItemCategory().name() : null;
            }
            return com.example.hms.payload.dto.billing.PayerShareLineDTO.builder()
                .itemId(line.getId())
                .description(BillingTextRenderer.render(line.getTextCode(), line.getTextArgs(),
                    line.getItemDescription()))
                .actCode(line.getPriceListItemId() != null ? actCodes.get(line.getPriceListItemId()) : null)
                .category(category)
                .quantity(line.getQuantity())
                .grossAmount(gross)
                .payerShare(payer)
                .patientShare(gross.subtract(payer).max(BigDecimal.ZERO))
                .preAuthorisationNumber(line.getPreAuthorisationNumber())
                .build();
        }
    }

    private static com.example.hms.payload.dto.billing.OpenPayerShareDTO openShare(InvoicePayerShare share,
                                                                                  BillingInvoice invoice,
                                                                                  ShareNames names) {
        Optional<PayerPlan> plan = names.plan(share.getPayerPlanId());
        Optional<Payer> payer = plan.flatMap(names::payer);
        Optional<PatientInsurance> coverage = names.coverage(share.getPatientInsuranceId());
        com.example.hms.model.Patient patient = invoice.getPatient();
        return com.example.hms.payload.dto.billing.OpenPayerShareDTO.builder()
            .id(share.getId())
            .invoiceId(invoice.getId())
            .invoiceNumber(invoice.getInvoiceNumber())
            .invoiceDate(invoice.getInvoiceDate())
            .patientName(patient != null ? patient.getFullName() : null)
            .patientMrn(patient != null ? patient.getMrnForHospital(names.hospitalId) : null)
            .payerId(payer.map(Payer::getId).orElse(null))
            .payerName(payer.map(Payer::getName).orElse(null))
            .payerType(payer.map(Payer::getPayerType).map(Enum::name).orElse(null))
            .planId(plan.map(PayerPlan::getId).orElse(null))
            .planName(plan.map(PayerPlan::getName).orElse(null))
            .policyNumber(coverage.map(PatientInsurance::getPolicyNumber).orElse(null))
            .subscriberName(coverage.map(PatientInsurance::getSubscriberName).orElse(null))
            .subscriberRelationship(coverage.map(PatientInsurance::getSubscriberRelationship).orElse(null))
            .coverageVerifiedAt(coverage.map(PatientInsurance::getVerifiedAt).orElse(null))
            .amount(share.getAmount())
            .status(share.getStatus().name())
            .createdAt(share.getCreatedAt())
            .paidAmount(share.getPaidAmount())
            .rejectedAmount(share.getRejectedAmount())
            .writtenOffAmount(share.getWrittenOffAmount())
            .openAmount(share.getStatus().isLive() ? share.openAmount() : BigDecimal.ZERO)
            .rejectionReason(share.getRejectionReason())
            .decidedAt(share.getDecidedAt())
            .claimBatchId(names.batchId(share.getId()))
            .claimBatchNumber(names.batchNumber(share.getId()))
            .lines(names.claimedLines(share))
            .build();
    }

    /** The shares of an invoice of the acting hospital (404 for another hospital's invoice). */
    @Transactional(readOnly = true)
    public List<InvoicePayerShareDTO> list(UUID invoiceId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
            .filter(found -> activeHospitalId == null
                || (found.getHospital() != null && activeHospitalId.equals(found.getHospital().getId())))
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_NOT_FOUND, invoiceId));
        return readShares(invoice);
    }

    /**
     * QA D228 class: the payers' share standing when a payment was received, for its receipt: every share written by
     * then and not rejected or cancelled before it.
     */
    public BigDecimal shareAt(BillingInvoice invoice, LocalDateTime at) {
        if (invoice == null || invoice.getId() == null || invoice.getHospital() == null || at == null) {
            return InvoiceBalance.payerShareOf(invoice);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (InvoicePayerShare share : shareRepository.findByInvoiceIdAndHospitalIdOrderByCreatedAtAsc(
                invoice.getId(), invoice.getHospital().getId())) {
            boolean writtenBy = share.getCreatedAt() == null || !share.getCreatedAt().isAfter(at);
            boolean endedBefore = !share.getStatus().isLive() && share.getDecidedAt() != null
                && !share.getDecidedAt().isAfter(at);
            if (writtenBy && !endedBefore) {
                sum = sum.add(share.getAmount()).subtract(rebilledBy(share, at));
            }
        }
        return sum;
    }

    /**
     * Insurer claims plan PR 1 (QA D2712): the payers' shares standing when a payment was received (as
     * {@link #shareAt}), with their payer, plan and policy, so the receipt names who covered what at its moment.
     */
    @Transactional(readOnly = true)
    public List<InvoicePayerShareDTO> sharesStandingAt(BillingInvoice invoice, LocalDateTime at) {
        if (invoice == null || invoice.getId() == null || invoice.getHospital() == null) {
            return List.of();
        }
        Set<UUID> standing = new java.util.HashSet<>();
        for (InvoicePayerShare share : shareRepository.findByInvoiceIdAndHospitalIdOrderByCreatedAtAsc(
                invoice.getId(), invoice.getHospital().getId())) {
            boolean writtenBy = at == null || share.getCreatedAt() == null || !share.getCreatedAt().isAfter(at);
            boolean endedBefore = !share.getStatus().isLive()
                && (at == null || (share.getDecidedAt() != null && !share.getDecidedAt().isAfter(at)));
            if (writtenBy && !endedBefore) {
                standing.add(share.getId());
            }
        }
        return readShares(invoice).stream().filter(share -> standing.contains(share.getId())).toList();
    }

    /* -------------------------------------------------------- internals */

    /** The service date: the invoice date (the checkout day for a visit; D-4). */
    private LocalDate serviceDay(BillingInvoice invoice) {
        return invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : LocalDate.now(clock);
    }

    /** The invoice's lines in a fixed order (created, then id), so a cap is taken from them the same way each time. */
    private List<InvoiceItem> linesOf(BillingInvoice invoice) {
        List<InvoiceItem> lines = itemRepository.findByBillingInvoiceId(invoice.getId());
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
            .sorted(Comparator.comparing(InvoiceItem::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(line -> String.valueOf(line.getId())))
            .toList();
    }

    /**
     * The coverage that applies on {@code day}: the patient's coverages held at this hospital that name a payer plan
     * and whose own dates cover the day, primary first; the first whose plan is offered that day with terms in force.
     */
    Optional<Coverage> coverageOn(UUID patientId, UUID hospitalId, LocalDate day) {
        List<PatientInsurance> candidates = insuranceRepository.findByPatient_IdAndAssignment_Hospital_Id(patientId,
            hospitalId);
        if (candidates == null) {
            return Optional.empty();
        }
        // QA D3250: the same order the desk reads ({@link CoverageOrder}), so the coverage billed is the one shown.
        List<PatientInsurance> ordered = CoverageOrder.ordered(candidates, day).stream()
            .filter(insurance -> insurance.getPayerPlanId() != null)
            .toList();
        for (PatientInsurance insurance : ordered) {
            Optional<Coverage> coverage = planOn(insurance, hospitalId, day);
            if (coverage.isPresent()) {
                return coverage;
            }
        }
        return Optional.empty();
    }

    /**
     * QA D3250 (r70): the coverage an invoice issued on {@code day} would bill, by the same rule as
     * {@link #applyAtIssue}: what the desk attests at check-in and shows in its snapshot. Empty when none applies.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<PatientInsurance> billedCoverage(UUID patientId, UUID hospitalId, LocalDate day) {
        if (patientId == null || hospitalId == null || day == null) {
            return Optional.empty();
        }
        return coverageOn(patientId, hospitalId, day).map(Coverage::insurance);
    }

    /**
     * What the patient's coverage would pay of one charge of {@code amount} on {@code day}, by the rule an invoice
     * uses ({@link CoverageCalculator}: the plan's terms and rules, the per-invoice and annual ceilings). Read-only:
     * an estimate the desk and the pharmacy show and record (QA D3258: the check-in's suggested copay; QA D3408: the
     * insurer share of a pharmacy fill left on a third-party cover). Empty when no coverage with a plan applies.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<CoverageQuote> quote(UUID patientId, UUID hospitalId, LocalDate day, BigDecimal amount,
                                         PriceListCategory category, UUID priceListItemId) {
        return quoteExcluding(patientId, hospitalId, day, amount, category, priceListItemId, null);
    }

    /**
     * {@link #quote} for a pharmacy fill of {@code excludedPrescriptionId}: the plan's annual ceiling counts every
     * other pharmacy share of the patient that year (review of #1284), never this prescription's own.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<CoverageQuote> quoteForPrescription(UUID patientId, UUID hospitalId, LocalDate day,
                                                        BigDecimal amount, UUID excludedPrescriptionId) {
        return quoteExcluding(patientId, hospitalId, day, amount, null, null, excludedPrescriptionId);
    }

    @SuppressWarnings("java:S107") // the quote's own inputs; a parameter object would only rename them
    private Optional<CoverageQuote> quoteExcluding(UUID patientId, UUID hospitalId, LocalDate day, BigDecimal amount,
                                                   PriceListCategory category, UUID priceListItemId,
                                                   UUID excludedPrescriptionId) {
        if (patientId == null || hospitalId == null || day == null) {
            return Optional.empty();
        }
        Optional<Coverage> found = coverageOn(patientId, hospitalId, day);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Coverage coverage = found.get();
        BigDecimal charge = amount != null ? amount.max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal payerShare = BigDecimal.ZERO;
        if (charge.signum() > 0) {
            UUID lineId = new UUID(0L, 0L);
            BigDecimal used = nonNull(shareRepository.sumUsedInYear(coverage.insurance().getId(), hospitalId,
                day.getYear(), lineId)).add(pharmacyUsed(patientId, hospitalId, day.getYear(), excludedPrescriptionId));
            payerShare = CoverageCalculator.compute(
                List.of(new CoverageCalculator.Line(lineId, charge, 1, category, priceListItemId)),
                coverage.terms(), coverage.rules(), used).total();
        }
        String payerName = coverage.plan().getPayerId() == null ? null
            : payerRepository.findByIdAndHospitalId(coverage.plan().getPayerId(), hospitalId)
                .map(Payer::getName).orElse(null);
        PatientInsurance insurance = coverage.insurance();
        return Optional.of(new CoverageQuote(insurance.getId(), payerName, coverage.plan().getName(),
            insurance.getPolicyNumber(), insurance.getEffectiveDate(), insurance.getExpirationDate(),
            coverage.terms().getDefaultCoveragePercent(), charge, payerShare,
            charge.subtract(payerShare).max(BigDecimal.ZERO)));
    }

    /**
     * One coverage's answer for one charge: who pays, under which plan and policy, and the split.
     *
     * @param defaultPercent the plan's default coverage percent on the day (a rule may differ for the charge)
     */
    public record CoverageQuote(UUID insuranceId, String payerName, String planName, String policyNumber,
                                LocalDate effectiveDate, LocalDate expirationDate, Integer defaultPercent,
                                BigDecimal amount, BigDecimal payerShare, BigDecimal patientShare) {
    }

    /** The share's own coverage and plan on {@code day}, or null when the plan no longer has terms that day. */
    private Coverage sameCoverage(InvoicePayerShare share, UUID hospitalId, LocalDate day) {
        PatientInsurance insurance = insuranceRepository.findByIdAndAssignment_Hospital_Id(share.getPatientInsuranceId(),
            hospitalId).orElse(null);
        if (insurance == null) {
            return null;
        }
        return planOn(insurance, hospitalId, day)
            .filter(coverage -> coverage.plan().getId().equals(share.getPayerPlanId()))
            .orElse(null);
    }

    private Optional<Coverage> planOn(PatientInsurance insurance, UUID hospitalId, LocalDate day) {
        Optional<PayerPlan> plan = planRepository.findByIdAndHospitalId(insurance.getPayerPlanId(), hospitalId)
            .filter(found -> found.coversDay(day));
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        UUID planId = plan.get().getId();
        Optional<PayerPlanTerms> terms = termsRepository
            .findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(planId, hospitalId).stream()
            .filter(version -> version.coversDay(day))
            .reduce((first, second) -> second);
        if (terms.isEmpty()) {
            return Optional.empty();
        }
        List<PayerPlanRule> rules = ruleRepository
            .findByPlanIdAndHospitalIdOrderByEffectiveFromAscCreatedAtAsc(planId, hospitalId).stream()
            .filter(rule -> rule.coversDay(day))
            .toList();
        return Optional.of(new Coverage(insurance, plan.get(), terms.get(), rules));
    }

    private CoverageCalculator.Result compute(BillingInvoice invoice, UUID hospitalId, List<InvoiceItem> lines,
                                              Coverage coverage, LocalDate day) {
        Map<UUID, PriceListCategory> categories = categoriesOf(lines, hospitalId);
        List<CoverageCalculator.Line> input = lines.stream()
            .map(line -> {
                PriceListCategory category = categoryOf(line, categories);
                return new CoverageCalculator.Line(line.getId(), coveredNet(category, netOf(line)),
                    line.getQuantity() != null ? line.getQuantity() : 1, category, line.getPriceListItemId());
            })
            .toList();
        BigDecimal used = nonNull(shareRepository.sumUsedInYear(coverage.insurance().getId(), hospitalId,
            day.getYear(), invoice.getId()))
            .add(pharmacyUsed(invoice.getPatient() != null ? invoice.getPatient().getId() : null, hospitalId,
                day.getYear(), null));
        return CoverageCalculator.compute(input, coverage.terms(), coverage.rules(), used);
    }

    private static BigDecimal nonNull(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    /**
     * Review of #1284: the pharmacy insurer shares (claims, covers) of the patient that year use the same annual
     * ceiling as the invoices. A required setter; a service built by hand in a unit test counts none.
     */
    private com.example.hms.service.pharmacy.PharmacyCoverUsage pharmacyCoverUsage;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPharmacyCoverUsage(com.example.hms.service.pharmacy.PharmacyCoverUsage pharmacyCoverUsage) {
        this.pharmacyCoverUsage = pharmacyCoverUsage;
    }

    /**
     * Insurer claims PR 1 (review of #1353): what each share claimed of each line, kept with the share. Setter
     * injected; a service built by hand in a unit test keeps none (the list then reads the live split).
     */
    private com.example.hms.repository.InvoicePayerShareLineRepository shareLineRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setShareLineRepository(com.example.hms.repository.InvoicePayerShareLineRepository shareLineRepository) {
        this.shareLineRepository = shareLineRepository;
    }

    /** The share's own per-line rows, from the computation that wrote it (whole francs, more than 0 only). */
    private void writeShareLines(InvoicePayerShare share, CoverageCalculator.Result result) {
        if (shareLineRepository == null || share.getId() == null) {
            return;
        }
        List<com.example.hms.model.InvoicePayerShareLine> rows = result.lines().stream()
            .filter(line -> line.id() != null && line.share() != null && line.share().signum() > 0)
            .map(line -> com.example.hms.model.InvoicePayerShareLine.builder()
                .hospitalId(share.getHospitalId())
                .shareId(share.getId())
                .invoiceItemId(line.id())
                .amount(line.share())
                .build())
            .toList();
        if (!rows.isEmpty()) {
            shareLineRepository.saveAll(rows);
        }
    }

    /**
     * Review of #1353 (F1): a credit note left the share's total as it was but moved what it pays of each line (under
     * a per-invoice or annual ceiling the lines are filled in order). The share's rows are replaced by the new split
     * so « Parts assureurs » lists what the invoice lines now say; nothing is written when the split is unchanged.
     */
    private boolean resplitShareLines(InvoicePayerShare share, CoverageCalculator.Result result) {
        if (shareLineRepository == null || share.getId() == null || sameSplit(share, result)) {
            return false;
        }
        shareLineRepository.deleteByShareAndHospital(share.getId(), share.getHospitalId());
        writeShareLines(share, result);
        return true;
    }

    /** Whether the share's own rows already say what {@code result} gives each line. True when no rows are kept. */
    private boolean sameSplit(InvoicePayerShare share, CoverageCalculator.Result result) {
        if (shareLineRepository == null || share.getId() == null) {
            return true;
        }
        Map<UUID, BigDecimal> stored = new HashMap<>();
        for (com.example.hms.model.InvoicePayerShareLine row
                : shareLineRepository.findByShareIdAndHospitalId(share.getId(), share.getHospitalId())) {
            stored.put(row.getInvoiceItemId(), row.getAmount());
        }
        Map<UUID, BigDecimal> now = new HashMap<>();
        for (CoverageCalculator.LineShare line : result.lines()) {
            if (line.id() != null && line.share() != null && line.share().signum() > 0) {
                now.put(line.id(), line.share());
            }
        }
        return stored.size() == now.size() && now.entrySet().stream()
            .allMatch(entry -> stored.containsKey(entry.getKey())
                && stored.get(entry.getKey()).compareTo(entry.getValue()) == 0);
    }

    /* ------------------------------------------------- insurer claims (V455) */

    private com.example.hms.service.billing.claim.InsurerClaimDraftSync claimDraftSync;
    private com.example.hms.repository.InsurerClaimLineRepository claimLineRepository;
    private com.example.hms.repository.InsurerClaimBatchRepository claimBatchRepository;
    private com.example.hms.repository.InsurerClaimRefusalRepository claimRefusalRepository;

    /**
     * Insurer claims plan PRs 2 and 3: the bordereaux a share is on, and what was re-billed of it. Setter injected;
     * a service built by hand in a unit test knows no bordereau.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setInsurerClaims(com.example.hms.service.billing.claim.InsurerClaimDraftSync claimDraftSync,
                                 com.example.hms.repository.InsurerClaimLineRepository claimLineRepository,
                                 com.example.hms.repository.InsurerClaimBatchRepository claimBatchRepository,
                                 com.example.hms.repository.InsurerClaimRefusalRepository claimRefusalRepository) {
        this.claimDraftSync = claimDraftSync;
        this.claimLineRepository = claimLineRepository;
        this.claimBatchRepository = claimBatchRepository;
        this.claimRefusalRepository = claimRefusalRepository;
    }

    private void followOnDrafts(UUID previousShareId, InvoicePayerShare current, UUID hospitalId) {
        if (claimDraftSync != null) {
            claimDraftSync.follow(previousShareId, current, hospitalId);
        }
    }

    /**
     * What had been re-billed to the patient of a live share by {@code at} (a refused bordereau line): that part had
     * already left the payers' share at that moment. Zero for a share nothing was re-billed of.
     */
    private BigDecimal rebilledBy(InvoicePayerShare share, LocalDateTime at) {
        if (claimRefusalRepository == null || !share.getStatus().isLive() || share.getRejectedAmount() == null
                || share.getRejectedAmount().signum() == 0) {
            return BigDecimal.ZERO;
        }
        return nonNull(claimRefusalRepository.sumRebilledOfShareBy(share.getId(), share.getHospitalId(), at));
    }

    private BigDecimal pharmacyUsed(UUID patientId, UUID hospitalId, int year, UUID excludedPrescriptionId) {
        return pharmacyCoverUsage == null ? BigDecimal.ZERO
            : nonNull(pharmacyCoverUsage.usedInYear(patientId, hospitalId, year, excludedPrescriptionId));
    }

    /** The line total less its credit notes still in force (a discount lowers the patient's share only, D-6). */
    private BigDecimal netOf(InvoiceItem line) {
        BigDecimal total = line.getTotalPrice() != null ? line.getTotalPrice() : BigDecimal.ZERO;
        BigDecimal credited = line.getId() != null ? adjustmentRepository.sumCreditNotesByInvoiceItemId(line.getId())
            : null;
        return total.subtract(credited != null ? credited : BigDecimal.ZERO).max(BigDecimal.ZERO);
    }

    /** The price-list categories of the lines priced from the hospital's own price list. */
    private Map<UUID, PriceListCategory> categoriesOf(List<InvoiceItem> lines, UUID hospitalId) {
        Collection<UUID> ids = lines.stream().map(InvoiceItem::getPriceListItemId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return priceListItemRepository.findAllById(ids).stream()
            .filter(item -> hospitalId.equals(item.getHospitalId()))
            .collect(Collectors.toMap(PriceListItem::getId, PriceListItem::getCategory, (a, b) -> a));
    }

    /**
     * The line's price-list category: its item's when it was priced from (or, QA D3980, picked from) the hospital's
     * price list; otherwise what the line was posted for (QA D3603, r71: an imaging order priced by hand is still
     * IMAGING, whatever its price source). Null for a line that bills no act of the price list and was posted for
     * nothing a plan names: a line typed at the till with no item, whatever its label or item category (QA D3980,
     * r77: « Sérodiagnostic de Widal » typed by hand as a LAB_TEST line escaped the plan's exclusion of that act and
     * was claimed at the default rate). Such a line is never covered ({@link #coveredNet}).
     */
    static PriceListCategory categoryOf(InvoiceItem line, Map<UUID, PriceListCategory> categories) {
        PriceListCategory fromItem = line.getPriceListItemId() != null ? categories.get(line.getPriceListItemId())
            : null;
        if (fromItem != null) {
            return fromItem;
        }
        return line.getCoverageCategory();
    }

    /**
     * QA D3980 (r77): fail closed. What a plan may cover of a line: its net when the line resolves to a category
     * ({@link #categoryOf}), nothing otherwise. A line no plan rule can read (no act, no category) took the plan's
     * default rate, so an act the plan excludes, typed at the till, was claimed from the insurer; it is now the
     * patient's to pay, and the cashier bills an act by picking it from the price list.
     */
    static BigDecimal coveredNet(PriceListCategory category, BigDecimal net) {
        return category == null ? BigDecimal.ZERO : net;
    }

    private static void applyToLines(List<InvoiceItem> lines, CoverageCalculator.Result result) {
        Map<UUID, BigDecimal> byLine = result.lines().stream()
            .collect(Collectors.toMap(CoverageCalculator.LineShare::id, CoverageCalculator.LineShare::share));
        for (InvoiceItem line : lines) {
            line.setPayerShareAmount(byLine.getOrDefault(line.getId(), BigDecimal.ZERO));
        }
    }

    private InvoicePayerShare writeShare(BillingInvoice invoice, UUID hospitalId, UUID insuranceId, UUID planId,
                                         BigDecimal amount, LocalDate day, UUID actorId) {
        return shareRepository.saveAndFlush(InvoicePayerShare.builder()
            .invoiceId(invoice.getId())
            .hospitalId(hospitalId)
            .patientInsuranceId(insuranceId)
            .payerPlanId(planId)
            .amount(amount)
            .coverageYear(day.getYear())
            .status(PayerShareStatus.OPEN)
            .createdBy(actorId)
            .build());
    }

    private static void claimed(int updated) {
        if (updated != 1) {
            throw new ConflictException(MessageUtil.resolve("billing.payerShare.alreadyDecided"));
        }
    }

    /** Ids only in the audit row: no names, no amounts tied to a person (no PHI). */
    private void audit(AuditEventType type, UUID actorId, BillingInvoice invoice, UUID shareId, String what) {
        if (auditEventLogService == null) {
            return;
        }
        String invoiceIdText = String.valueOf(invoice.getId());
        // QA D2924 (r66): the invoice's hospital anchors the row for the hospital admin's audit log.
        UUID hospitalId = invoice.getHospital() != null ? invoice.getHospital().getId() : null;
        String description = what + ": share " + shareId + " on invoice " + invoiceIdText;
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(invoiceIdText)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Payer share audit {} not written for invoice {}: {}", type, invoiceIdText,
                    ex.getClass().getSimpleName());
            }
        });
    }
}
