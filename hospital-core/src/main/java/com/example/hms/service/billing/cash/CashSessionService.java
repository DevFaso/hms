package com.example.hms.service.billing.cash;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.CashSessionStatus;
import com.example.hms.enums.CashTill;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.CashSession;
import com.example.hms.model.CashSessionCount;
import com.example.hms.model.InvoiceRefund;
import com.example.hms.model.PaymentTransaction;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.PharmacyPayment;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.CashSessionCloseRequestDTO;
import com.example.hms.payload.dto.billing.CashSessionDTO;
import com.example.hms.repository.CashSessionCountRepository;
import com.example.hms.repository.CashSessionRepository;
import com.example.hms.repository.InvoiceRefundRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.security.context.ImpersonationLimits;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.billing.TariffResolver;
import com.example.hms.utility.CardNumbers;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Billing-money plan PR 10 (V309; QA r65 D2325, D2326, D2672, D2714): cash sessions.
 *
 * <p><b>Stamp.</b> Every money movement (an invoice payment taken or a declaration confirmed, a check-in copay, a
 * pharmacy dispense payment, a counter sale, a refund or a reversal paid out) calls {@link #stamp} in its own
 * transaction: the cashier's session for that till today is opened if needed, row-locked, and refused with 409 when
 * it is closed. {@code CashSessionStampGuardTest} keeps every writer on it.
 *
 * <p><b>Expected.</b> Per method (and mobile-money operator): money in - money out of the session. Money out is
 * only a refund (or a reversal) approved by another person and paid out; a reversal or a void never lowers what the
 * till expects by itself (QA D2702). The cash to count is the CASH line alone (QA D2714).
 *
 * <p><b>Close.</b> The session's cashier counts each method; a non-zero difference needs an explanation; the session
 * is then CLOSED and takes no new movement. A hospital admin or an accountant (not the cashier) reopens it with a
 * reason or marks it reviewed; earlier counts are kept, round by round.
 *
 * <p><b>Tenancy.</b> Everything is read within the acting (pinned) hospital; another hospital's session answers as a
 * missing one.
 */
@Slf4j
@Service
public class CashSessionService {

    static final String NOT_FOUND = "billing.cashSession.notFound";
    static final String AUDIT_ENTITY = "CashSession";
    static final int TEXT_MAX = 500;
    static final String IN = "IN";
    static final String OUT = "OUT";
    private static final String MOBILE_MONEY = "MOBILE_MONEY";
    private static final String FIELD_COUNTS = "counts";
    private static final String FIELD_COUNTED = "counted";
    private static final String FIELD_NOTE = "note";

    private final CashSessionRepository sessionRepository;
    private final CashSessionCountRepository countRepository;
    private final CashSessionWrites writes;
    private final PaymentTransactionRepository paymentRepository;
    private final InvoiceRefundRepository refundRepository;
    private final PharmacyPaymentRepository pharmacyPaymentRepository;
    private final PharmacySaleRepository saleRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final TariffResolver businessDays;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern; a parameter object would only hide them
    public CashSessionService(CashSessionRepository sessionRepository,
                              CashSessionCountRepository countRepository,
                              CashSessionWrites writes,
                              PaymentTransactionRepository paymentRepository,
                              InvoiceRefundRepository refundRepository,
                              PharmacyPaymentRepository pharmacyPaymentRepository,
                              PharmacySaleRepository saleRepository,
                              UserRepository userRepository,
                              RoleValidator roleValidator,
                              ActingScopeResolver actingScopeResolver,
                              TariffResolver businessDays,
                              AuditEventLogService auditEventLogService,
                              Clock clock) {
        this.sessionRepository = sessionRepository;
        this.countRepository = countRepository;
        this.writes = writes;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.pharmacyPaymentRepository = pharmacyPaymentRepository;
        this.saleRepository = saleRepository;
        this.userRepository = userRepository;
        this.roleValidator = roleValidator;
        this.actingScopeResolver = actingScopeResolver;
        this.businessDays = businessDays;
        this.auditEventLogService = auditEventLogService;
        this.clock = clock;
    }

    // ───────────────────────────── stamp ─────────────────────────────

    /**
     * The id of {@code cashierId}'s OPEN session for {@code till} today at {@code hospitalId}, opened if needed and
     * row-locked to the end of the caller's transaction. A closed session refuses the movement (409, nothing
     * written).
     *
     * <p>PR 10 review: never null. A movement with no identified cashier used to be written with no session (money
     * in no till); it is refused. So is money taken by someone who holds no till at the hospital (the platform
     * super-admin on expanded authorities): their session could never be counted and closed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID stamp(CashTill till, UUID hospitalId, UUID cashierId) {
        if (till == null || hospitalId == null || cashierId == null) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.cashSession.cashierUnknown"));
        }
        if (!roleValidator.canHoldTill(cashierId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.cashSession.tillHoldersOnly"));
        }
        LocalDate day = businessDays.today();
        CashSession session = sessionRepository.findLockedForDay(hospitalId, cashierId, till, day).orElse(null);
        if (session == null) {
            writes.insertIfAbsent(hospitalId, cashierId, till, day);
            session = sessionRepository.findLockedForDay(hospitalId, cashierId, till, day)
                .orElseThrow();
        }
        if (session.getStatus() != CashSessionStatus.OPEN) {
            throw new ConflictException(MessageUtil.resolve("billing.cashSession.closedForMovement"));
        }
        return session.getId();
    }

    // ───────────────────────────── reads ─────────────────────────────

    /** The signed-in user's sessions on {@code date} (today when null) at the acting hospital, every till. */
    @Transactional(readOnly = true)
    public List<CashSessionDTO> mine(LocalDate date) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID me = requireUser();
        LocalDate day = date != null ? date : businessDays.today();
        List<CashSession> rows = sessionRepository
            .findByHospitalIdAndCashierUserIdAndBusinessDateOrderByTillAsc(hospitalId, me, day);
        Map<UUID, String> names = names(rows);
        return rows.stream().map(s -> toDto(s, me, names, true)).toList();
    }

    /** Every session of the acting hospital on {@code date}, optionally one till: the supervisor's view. */
    @Transactional(readOnly = true)
    public List<CashSessionDTO> list(LocalDate date, CashTill till) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID me = requireSupervisorOrPlatformReader(hospitalId);
        LocalDate day = date != null ? date : businessDays.today();
        List<CashSession> rows = sessionRepository.findByHospitalIdAndBusinessDateOrderByTillAscOpenedAtAsc(
            hospitalId, day).stream().filter(s -> till == null || s.getTill() == till).toList();
        Map<UUID, String> names = names(rows);
        return rows.stream().map(s -> toDto(s, me, names, false)).toList();
    }

    /** One session, with its movements: its cashier or a supervisor of its hospital. */
    @Transactional(readOnly = true)
    public CashSessionDTO get(UUID id) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID me = requireUser();
        CashSession session = sessionRepository.findByIdAndHospitalId(id, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        requireOwnerOrSupervisor(session, me, hospitalId);
        return toDto(session, me, names(List.of(session)), true);
    }

    /** The sessions of a day with their movements (the day report: PDF and journal). Supervisors only. */
    @Transactional(readOnly = true)
    public DayReport dayReport(LocalDate date) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID me = requireSupervisorOrPlatformReader(hospitalId);
        LocalDate day = date != null ? date : businessDays.today();
        List<CashSession> rows = sessionRepository.findByHospitalIdAndBusinessDateOrderByTillAscOpenedAtAsc(
            hospitalId, day);
        Map<UUID, String> names = names(rows);
        return new DayReport(hospitalId, day, rows.stream().map(s -> toDto(s, me, names, true)).toList());
    }

    /** The acting (pinned) hospital the reads are scoped to. */
    public UUID actingHospital() {
        return actingScopeResolver.requirePinned();
    }

    /** A day's sessions of one hospital, every movement included. */
    public record DayReport(UUID hospitalId, LocalDate day, List<CashSessionDTO> sessions) {
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    /**
     * The session's cashier counts it and closes it. Every method the session expects must be counted; a counted
     * amount is whole francs, at least 0; a difference needs an explanation. The counts are stored as a new round.
     */
    @Transactional
    public CashSessionDTO close(UUID id, CashSessionCloseRequestDTO body) {
        ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "cash session close");
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID me = requireUser();
        CashSession session = sessionRepository.findLockedByIdAndHospitalId(id, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (!me.equals(session.getCashierUserId())) {
            if (!roleValidator.canSuperviseTills(me, hospitalId)) {
                throw new ResourceNotFoundException(NOT_FOUND);
            }
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.cashSession.ownerOnly"));
        }
        if (session.getStatus() != CashSessionStatus.OPEN) {
            throw new ConflictException(MessageUtil.resolve("billing.cashSession.notOpen"));
        }
        Figures figures = figures(session);
        List<CashSessionCount> rows = countedRows(session, figures, body, me);
        int round = (session.getCloseRound() != null ? session.getCloseRound() : 0) + 1;
        rows.forEach(r -> r.setCloseRound(round));
        countRepository.saveAll(rows);
        session.setCloseRound(round);
        session.setStatus(CashSessionStatus.CLOSED);
        session.setClosedAt(now());
        session.setClosedBy(me);
        sessionRepository.save(session);
        audit(AuditEventType.CASH_SESSION_CLOSED, me, session, "Cash session closed", null);
        return toDto(session, me, names(List.of(session)), true);
    }

    /** A hospital admin or an accountant (never the session's cashier) reopens a closed session, with a reason. */
    @Transactional
    public CashSessionDTO reopen(UUID id, String reason) {
        ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "cash session reopen");
        Supervisor actor = requireSupervisor();
        CashSession session = sessionRepository.findLockedByIdAndHospitalId(id, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        requireNotOwn(session, actor);
        if (session.getStatus() == CashSessionStatus.OPEN) {
            throw new ConflictException(MessageUtil.resolve("billing.cashSession.alreadyOpen"));
        }
        String why = requireNote(reason, "billing.cashSession.reopenReasonRequired");
        session.setStatus(CashSessionStatus.OPEN);
        session.setReopenedAt(now());
        session.setReopenedBy(actor.userId());
        session.setReopenReason(why);
        session.setReviewedAt(null);
        session.setReviewedBy(null);
        session.setReviewNote(null);
        sessionRepository.save(session);
        audit(AuditEventType.CASH_SESSION_REOPENED, actor.userId(), session, "Cash session reopened", why);
        return toDto(session, actor.userId(), names(List.of(session)), true);
    }

    /** A hospital admin or an accountant (never the session's cashier) marks a closed session reviewed. */
    @Transactional
    public CashSessionDTO review(UUID id, String note) {
        // PR 10 review: the review is the second pair of eyes on a till; a support session is never that pair.
        ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "cash session review");
        Supervisor actor = requireSupervisor();
        CashSession session = sessionRepository.findLockedByIdAndHospitalId(id, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        requireNotOwn(session, actor);
        if (session.getStatus() != CashSessionStatus.CLOSED) {
            throw new ConflictException(MessageUtil.resolve("billing.cashSession.notClosed"));
        }
        String text = trimToNull(note);
        if (text != null) {
            requireText(text, FIELD_NOTE);
        }
        session.setStatus(CashSessionStatus.REVIEWED);
        session.setReviewedAt(now());
        session.setReviewedBy(actor.userId());
        session.setReviewNote(text);
        sessionRepository.save(session);
        audit(AuditEventType.CASH_SESSION_REVIEWED, actor.userId(), session, "Cash session reviewed", text);
        return toDto(session, actor.userId(), names(List.of(session)), true);
    }

    // ───────────────────────────── figures ─────────────────────────────

    /** What a session took in and handed out, per method, and each movement. */
    record Figures(Map<String, CashSessionDTO.MethodLine> lines, List<CashSessionDTO.Movement> movements) {

        BigDecimal totalIn() {
            return lines.values().stream().map(CashSessionDTO.MethodLine::getInAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal totalOut() {
            return lines.values().stream().map(CashSessionDTO.MethodLine::getOutAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal cashToCount() {
            CashSessionDTO.MethodLine cash = lines.get(key("CASH", null));
            return cash != null ? cash.getExpected() : BigDecimal.ZERO;
        }
    }

    /** The key of a method line: the method, and the operator for mobile money. */
    static String key(String method, String operator) {
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        String o = operator == null || operator.isBlank() || !MOBILE_MONEY.equals(m) ? ""
            : operator.trim().toUpperCase(Locale.ROOT);
        return m + "|" + o;
    }

    Figures figures(CashSession session) {
        List<CashSessionDTO.Movement> movements = new ArrayList<>();
        UUID id = session.getId();
        List<PaymentTransaction> payments = paymentRepository.findByCashSessionIdOrderByCreatedAtAsc(id);
        if (payments != null) {
            payments.forEach(p -> movements.add(movement(p)));
        }
        List<PharmacyPayment> dispensePayments = pharmacyPaymentRepository.findByCashSessionIdOrderByCreatedAtAsc(id);
        if (dispensePayments != null) {
            dispensePayments.forEach(p -> movements.add(movement(p)));
        }
        List<PharmacySale> sales = saleRepository.findByCashSessionIdOrderByCreatedAtAsc(id);
        if (sales != null) {
            sales.forEach(sale -> movements.add(movement(sale)));
        }
        List<InvoiceRefund> refunds = refundRepository.findByCashSessionIdOrderByPaidAtAsc(id);
        if (refunds != null) {
            refunds.forEach(r -> movements.add(movement(r)));
        }
        movements.sort(Comparator.comparing(CashSessionDTO.Movement::getAt,
            Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<String, CashSessionDTO.MethodLine> lines = new LinkedHashMap<>();
        for (CashSessionDTO.Movement m : movements) {
            CashSessionDTO.MethodLine line = lines.computeIfAbsent(key(m.getMethod(), m.getOperator()),
                k -> CashSessionDTO.MethodLine.builder()
                    .method(m.getMethod())
                    .operator(MOBILE_MONEY.equals(m.getMethod()) ? m.getOperator() : null)
                    .inAmount(BigDecimal.ZERO).outAmount(BigDecimal.ZERO).expected(BigDecimal.ZERO).build());
            BigDecimal amount = m.getAmount() != null ? m.getAmount() : BigDecimal.ZERO;
            if (OUT.equals(m.getDirection())) {
                line.setOutCount(line.getOutCount() + 1);
                line.setOutAmount(line.getOutAmount().add(amount));
            } else {
                line.setInCount(line.getInCount() + 1);
                line.setInAmount(line.getInAmount().add(amount));
            }
            line.setExpected(line.getInAmount().subtract(line.getOutAmount()));
        }
        return new Figures(lines, movements);
    }

    private static CashSessionDTO.Movement movement(PaymentTransaction p) {
        BillingInvoice invoice = p.getInvoice();
        return CashSessionDTO.Movement.builder()
            .id(p.getId())
            .kind(invoice != null && invoice.isCheckInCopay() ? "COPAY" : "PAYMENT")
            .direction(IN)
            .at(p.getConfirmedAt() != null ? p.getConfirmedAt() : p.getCreatedAt())
            .document(invoice != null ? invoice.getInvoiceNumber() : null)
            .patientName(invoice != null && invoice.getPatient() != null ? invoice.getPatient().getFullName() : null)
            .method(p.getPaymentMethod() != null ? p.getPaymentMethod().name() : null)
            .operator(p.getMobileMoneyOperator() != null ? p.getMobileMoneyOperator().name() : null)
            .reference(p.getReferenceNumber())
            .amount(p.getAmount())
            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .build();
    }

    private static CashSessionDTO.Movement movement(PharmacyPayment p) {
        return CashSessionDTO.Movement.builder()
            .id(p.getId())
            .kind("PHARMACY_PAYMENT")
            .direction(IN)
            .at(p.getCreatedAt())
            .document(p.getReceiptNumber())
            .patientName(p.getPatient() != null ? p.getPatient().getFullName() : null)
            .method(p.getPaymentMethod() != null ? p.getPaymentMethod().name() : null)
            .operator(p.getMobileMoneyOperator() != null ? p.getMobileMoneyOperator().name() : null)
            .reference(p.getReferenceNumber())
            .amount(p.getAmount())
            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .build();
    }

    private static CashSessionDTO.Movement movement(PharmacySale s) {
        return CashSessionDTO.Movement.builder()
            .id(s.getId())
            .kind("COUNTER_SALE")
            .direction(IN)
            .at(s.getSaleDate() != null ? s.getSaleDate() : s.getCreatedAt())
            .document(null)
            .patientName(s.getPatient() != null ? s.getPatient().getFullName() : null)
            .method(s.getPaymentMethod() != null ? s.getPaymentMethod().name() : null)
            .operator(s.getMobileMoneyOperator() != null ? s.getMobileMoneyOperator().name() : null)
            .reference(s.getReferenceNumber())
            .amount(s.getTotalAmount())
            .status(s.getStatus() != null ? s.getStatus().name() : null)
            .build();
    }

    private static CashSessionDTO.Movement movement(InvoiceRefund r) {
        BillingInvoice invoice = r.getInvoice();
        return CashSessionDTO.Movement.builder()
            .id(r.getId())
            .kind(r.getReversedPaymentId() != null ? "REVERSAL" : "REFUND")
            .direction(OUT)
            .at(r.getPaidAt())
            .document(refundDocument(r, invoice))
            .patientName(invoice != null && invoice.getPatient() != null ? invoice.getPatient().getFullName() : null)
            .method(r.getMethod() != null ? r.getMethod().name() : null)
            .operator(r.getMobileMoneyOperator() != null ? r.getMobileMoneyOperator().name() : null)
            .reference(r.getReference())
            .amount(r.getAmount())
            .status(r.getStatus() != null ? r.getStatus().name() : null)
            .build();
    }

    /** The receipt number of a refund paid out, else its invoice's number. */
    private static String refundDocument(InvoiceRefund r, BillingInvoice invoice) {
        if (r.getReceiptNumber() != null) {
            return r.getReceiptNumber();
        }
        return invoice != null ? invoice.getInvoiceNumber() : null;
    }

    /** The counted rows of a close: every expected method counted, whole francs, a difference explained. */
    private List<CashSessionCount> countedRows(CashSession session, Figures figures, CashSessionCloseRequestDTO body,
                                               UUID me) {
        Map<String, CashSessionCloseRequestDTO.CountedLine> given = givenCounts(body,
            figures.lines().containsKey(NOT_STATED_LINE));
        Set<String> keys = new java.util.LinkedHashSet<>(figures.lines().keySet());
        keys.addAll(given.keySet());
        List<CashSessionCount> rows = new ArrayList<>();
        for (String k : keys) {
            rows.add(countRow(session, figures.lines().get(k), given.get(k), me));
        }
        return rows;
    }

    /**
     * PR 10 review: the mobile-money line with no operator. A patient's own declaration from the app carries none
     * ({@code MobileMoneyOperators.keep}); once a cashier confirms it, its money is in that cashier's session, and a
     * close that refused a count without an operator could never close that till. The line is counted as it stands,
     * « Mobile Money (opérateur non précisé) », only when the session holds such money; a count without an operator
     * for money the session never took is still refused.
     */
    static final String NOT_STATED_LINE = key(MOBILE_MONEY, null);

    /** What was counted, by method line; a line counted twice is refused. */
    private static Map<String, CashSessionCloseRequestDTO.CountedLine> givenCounts(CashSessionCloseRequestDTO body,
                                                                                   boolean holdsNotStated) {
        Map<String, CashSessionCloseRequestDTO.CountedLine> given = new LinkedHashMap<>();
        if (body == null || body.getCounts() == null) {
            return given;
        }
        for (CashSessionCloseRequestDTO.CountedLine line : body.getCounts()) {
            if (line == null || line.getMethod() == null || line.getMethod().isBlank()) {
                continue;
            }
            // An insurer's share is never money at a till (QA D1611); mobile money names its operator (PR 9).
            com.example.hms.enums.PaymentMethod method = parseMethod(line.getMethod());
            com.example.hms.service.payment.TillPaymentMethods.requireReceivedAtTill(method, FIELD_COUNTS);
            boolean notStated = holdsNotStated && method == com.example.hms.enums.PaymentMethod.MOBILE_MONEY
                && line.getOperator() == null;
            com.example.hms.enums.MobileMoneyOperator op = notStated ? null
                : com.example.hms.service.payment.MobileMoneyOperators.require(method, line.getOperator(),
                    FIELD_COUNTS);
            String operator = op != null ? op.name() : null;
            if (given.put(key(line.getMethod(), operator), line) != null) {
                throw new FieldBusinessException(FIELD_COUNTS, "billing.cashSession.countTwice",
                    methodLabel(line.getMethod(), operator));
            }
        }
        return given;
    }

    /** One counted line against what the session expects for it (0 for a method it never took). */
    private static CashSessionCount countRow(CashSession session, CashSessionDTO.MethodLine line,
                                             CashSessionCloseRequestDTO.CountedLine counted, UUID me) {
        String method;
        String operator;
        if (line != null) {
            method = line.getMethod();
            operator = line.getOperator();
        } else {
            method = counted.getMethod().trim().toUpperCase(Locale.ROOT);
            operator = MOBILE_MONEY.equals(method) && counted.getOperator() != null
                ? counted.getOperator().name() : null;
        }
        String label = methodLabel(method, operator);
        if (counted == null || counted.getCounted() == null) {
            throw new FieldBusinessException(FIELD_COUNTED, "billing.cashSession.countRequired", label);
        }
        BigDecimal amount = counted.getCounted();
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new FieldBusinessException(FIELD_COUNTED, "billing.cashSession.countWhole", label);
        }
        BigDecimal expected = line != null ? line.getExpected() : BigDecimal.ZERO;
        BigDecimal difference = amount.subtract(expected);
        String explanation = trimToNull(counted.getExplanation());
        if (difference.signum() != 0 && explanation == null) {
            throw new FieldBusinessException("explanation", "billing.cashSession.explanationRequired", label);
        }
        if (explanation != null) {
            requireText(explanation, "explanation");
        }
        return CashSessionCount.builder()
            .sessionId(session.getId())
            .method(method)
            .mobileMoneyOperator(operator)
            .expectedAmount(expected)
            .countedAmount(amount)
            .difference(difference)
            .explanation(explanation)
            .countedBy(me)
            .build();
    }

    private static com.example.hms.enums.PaymentMethod parseMethod(String raw) {
        for (com.example.hms.enums.PaymentMethod candidate : com.example.hms.enums.PaymentMethod.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        throw new FieldBusinessException(FIELD_COUNTS, "billing.payment.methodUnsupported");
    }

    private static String methodLabel(String method, String operator) {
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (MOBILE_MONEY.equals(m)) {
            String o = operator == null || operator.isBlank() ? "NOT_STATED" : operator.trim().toUpperCase(Locale.ROOT);
            return MessageUtil.resolveOrRaw("pdf.payment.operator." + o);
        }
        return MessageUtil.resolveOrRaw("pdf.payment.method." + m);
    }

    // ───────────────────────────── mapping ─────────────────────────────

    private CashSessionDTO toDto(CashSession s, UUID me, Map<UUID, String> names, boolean withMovements) {
        Figures figures = figures(s);
        List<CashSessionCount> stored = countRepository.findBySessionIdOrderByCloseRoundAscMethodAsc(s.getId());
        List<CashSessionCount> counts = stored != null ? stored : List.of();
        int round = s.getCloseRound() != null ? s.getCloseRound() : 0;
        List<CashSessionCount> latest = counts.stream()
            .filter(c -> c.getCloseRound() != null && c.getCloseRound() == round).toList();
        BigDecimal difference = round == 0 ? null : latest.stream()
            .map(CashSessionCount::getDifference)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // PR 10 review: the sum nets a shortage against a surplus (cash -500, Orange Money +500 = 0). The drawer's
        // own difference and the number of lines that differ are given beside it.
        BigDecimal cashDifference = round == 0 ? null : latest.stream()
            .filter(c -> "CASH".equals(c.getMethod()))
            .map(CashSessionCount::getDifference)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        int differenceLines = round == 0 ? 0 : (int) latest.stream()
            .filter(c -> c.getDifference() != null && c.getDifference().signum() != 0).count();
        return CashSessionDTO.builder()
            .id(s.getId())
            .till(s.getTill() != null ? s.getTill().name() : null)
            .cashierId(s.getCashierUserId())
            .cashierName(names.get(s.getCashierUserId()))
            .businessDate(s.getBusinessDate())
            .status(s.getStatus() != null ? s.getStatus().name() : null)
            .openedAt(s.getOpenedAt())
            .closeRound(round)
            .closedAt(s.getClosedAt())
            .closedByName(s.getClosedBy() != null ? names.get(s.getClosedBy()) : null)
            .reviewedAt(s.getReviewedAt())
            .reviewedByName(s.getReviewedBy() != null ? names.get(s.getReviewedBy()) : null)
            .reviewNote(s.getReviewNote())
            .reopenedAt(s.getReopenedAt())
            .reopenedByName(s.getReopenedBy() != null ? names.get(s.getReopenedBy()) : null)
            .reopenReason(s.getReopenReason())
            .mine(me != null && me.equals(s.getCashierUserId()))
            .methods(new ArrayList<>(figures.lines().values()))
            .totalIn(figures.totalIn())
            .totalOut(figures.totalOut())
            .totalExpected(figures.totalIn().subtract(figures.totalOut()))
            .cashToCount(figures.cashToCount())
            .totalDifference(difference)
            .cashDifference(cashDifference)
            .differenceLines(differenceLines)
            .counts(counts.stream().map(c -> CashSessionDTO.Count.builder()
                .closeRound(c.getCloseRound() != null ? c.getCloseRound() : 0)
                .method(c.getMethod())
                .operator(c.getMobileMoneyOperator())
                .expected(c.getExpectedAmount())
                .counted(c.getCountedAmount())
                .difference(c.getDifference())
                .explanation(c.getExplanation())
                .build()).toList())
            .movements(withMovements ? figures.movements() : List.of())
            .build();
    }

    private Map<UUID, String> names(List<CashSession> rows) {
        Set<UUID> ids = new HashSet<>();
        for (CashSession s : rows) {
            ids.add(s.getCashierUserId());
            if (s.getClosedBy() != null) {
                ids.add(s.getClosedBy());
            }
            if (s.getReviewedBy() != null) {
                ids.add(s.getReviewedBy());
            }
            if (s.getReopenedBy() != null) {
                ids.add(s.getReopenedBy());
            }
        }
        ids.remove(null);
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User user : userRepository.findAllById(ids)) {
                String joined = (Objects.toString(user.getFirstName(), "") + " "
                    + Objects.toString(user.getLastName(), "")).trim();
                names.put(user.getId(), joined.isEmpty() ? null : joined);
            }
        }
        return names;
    }

    // ───────────────────────────── access ─────────────────────────────

    record Supervisor(UUID userId, UUID hospitalId) {
    }

    private UUID requireUser() {
        UUID me = roleValidator.getCurrentUserId();
        if (me == null) {
            throw new AccessDeniedException("Access denied");
        }
        return me;
    }

    private Supervisor requireSupervisor() {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID me = requireUser();
        if (!roleValidator.canSuperviseTills(me, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.cashSession.supervisorsOnly"));
        }
        return new Supervisor(me, hospitalId);
    }

    /** A supervisor of the hospital, or the verified platform super-admin reading it. */
    private UUID requireSupervisorOrPlatformReader(UUID hospitalId) {
        UUID me = requireUser();
        if (actingScopeResolver.isVerifiedSuperAdmin() || roleValidator.canSuperviseTills(me, hospitalId)) {
            return me;
        }
        throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.cashSession.supervisorsOnly"));
    }

    private void requireOwnerOrSupervisor(CashSession session, UUID me, UUID hospitalId) {
        if (me.equals(session.getCashierUserId()) || actingScopeResolver.isVerifiedSuperAdmin()
                || roleValidator.canSuperviseTills(me, hospitalId)) {
            return;
        }
        // Another cashier's session answers as a missing one.
        throw new ResourceNotFoundException(NOT_FOUND);
    }

    /** Four eyes: the cashier never reopens or reviews their own till. */
    private static void requireNotOwn(CashSession session, Supervisor actor) {
        if (actor.userId().equals(session.getCashierUserId())) {
            throw new ConflictException(MessageUtil.resolve("billing.cashSession.notOwnSession"));
        }
    }

    // ───────────────────────────── values ─────────────────────────────

    private static String requireNote(String raw, String requiredKey) {
        String text = trimToNull(raw);
        if (text == null) {
            throw new FieldBusinessException(FIELD_NOTE, requiredKey);
        }
        requireText(text, FIELD_NOTE);
        return text;
    }

    private static void requireText(String text, String field) {
        if (text.length() > TEXT_MAX) {
            throw new FieldBusinessException(field, "billing.cashSession.textTooLong", TEXT_MAX);
        }
        CardNumbers.requireNone(text, field);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Ids in the description; a typed reason or note in the details, never the description. */
    private void audit(AuditEventType type, UUID actorId, CashSession session, String what, String text) {
        if (auditEventLogService == null) {
            return;
        }
        String sessionId = String.valueOf(session.getId());
        UUID hospitalId = session.getHospitalId();
        Map<String, String> details = text == null ? null : Map.of("reason", text);
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorId)
                    .hospitalId(hospitalId)
                    .eventDescription(what + ": session " + sessionId)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(sessionId)
                    .details(details)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Cash session audit {} not written for {}: {}", type, sessionId,
                    ex.getClass().getSimpleName());
            }
        });
    }
}
