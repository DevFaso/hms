package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.enums.PharmacySaleStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.PharmacyRefund;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.model.pharmacy.SaleLine;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundPayRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundReasonRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRefundRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacyRefundRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.payment.MobileMoneyOperators;
import com.example.hms.service.payment.PaymentReferenceRegistry;
import com.example.hms.service.payment.TillPaymentMethods;
import com.example.hms.utility.CardNumbers;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * QA D2676 (r65, V311; billing-money plan PR 13): money given back for a patient return, under the same two-person
 * rule as an invoice refund (owner decision D-3).
 *
 * <p><b>What it pays for.</b> A refund names the RETURN stock movement that put the medication back, and that movement
 * names the counter sale or the dispense it returns. The amount is the server's: the returned quantity at the price the
 * patient paid (the sale line's price, or the pharmacy's list price for a dispense), bounded by what the patient paid on
 * that sale or dispense less its other live refunds. One live refund per return movement.
 *
 * <p><b>Steps.</b> REQUESTED (a pharmacist, a hospital admin, an accountant or a cashier, with a reason), APPROVED or
 * REJECTED by ANOTHER hospital admin or accountant (no self-approval; the V311 CHECK holds it in the database too),
 * PAID when handed to the patient (money out on {@code paid_at}), or CANCELLED before payment. A sale whose live
 * refunds reach its total becomes REFUNDED (the status had no path before, plan finding 8).
 *
 * <p><b>Who and where.</b> The roles are checked on the assignment at the acting hospital before any lookup; every
 * row is read by its hospital, and another hospital's answers as a missing one. A support session impersonating a
 * user never moves money. No card data: reason and reference are refused when they hold a card number.
 */
@Service
@RequiredArgsConstructor
public class PharmacyRefundService {

    static final String NOT_FOUND = "pharmacy.refund.notFound";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_REFERENCE = "reference";
    private static final String FIELD_METHOD = "method";
    private static final String AUDIT_ENTITY = "PHARMACY_REFUND";
    private static final int TEXT_MAX = 500;
    private static final int REFERENCE_MAX = 120;

    /** Asked, approved or paid: they hold part of what a sale or a dispense can give back. */
    static final Set<PharmacyRefundStatus> LIVE = EnumSet.of(PharmacyRefundStatus.REQUESTED,
            PharmacyRefundStatus.APPROVED, PharmacyRefundStatus.PAID);
    static final Set<PharmacyRefundStatus> CANCELLABLE_STATUSES = EnumSet.of(PharmacyRefundStatus.REQUESTED,
            PharmacyRefundStatus.APPROVED);

    private final PharmacyRefundRepository refundRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final PharmacySaleRepository saleRepository;
    private final DispenseRepository dispenseRepository;
    private final PharmacyPaymentRepository paymentRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final UserRepository userRepository;
    private final PaymentReferenceRegistry referenceRegistry;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;
    private final com.example.hms.service.AuditEventLogService auditEventLogService;
    private final Clock clock;

    record Actor(UUID userId, UUID hospitalId) {
    }

    /** What the return gives back and from what it was paid. */
    private record Source(UUID saleId, UUID dispenseId, BigDecimal unitPrice, BigDecimal paid,
                          PharmacyPaymentMethod paidBy) {
    }

    // ───────────────────────────── writes ─────────────────────────────

    /** Asks for the money of a patient return to be given back (REQUESTED). */
    @Transactional
    public PharmacyRefundDTO request(PharmacyRefundRequestDTO body) {
        Actor actor = requireActor(roleValidator::canRequestPharmacyRefund, "pharmacy.refund.requestersOnly");
        PharmacyRefundRequestDTO request = body != null ? body : new PharmacyRefundRequestDTO();
        String reason = trimToNull(request.getReason());
        RequiredFields.check()
                .require(request.getReturnTransactionId() != null, "returnTransactionId",
                        "pharmacy.refund.returnRequired")
                .require(reason != null, FIELD_REASON, "billing.refund.reasonRequired")
                .orThrow();
        requireText(reason, FIELD_REASON);

        StockTransaction ret = stockTransactionRepository.findById(request.getReturnTransactionId())
                .filter(t -> t.getTransactionType() == StockTransactionType.RETURN)
                .filter(t -> inHospital(t.getInventoryItem(), actor.hospitalId()))
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.refund.returnNotFound"));
        if (ret.getReferenceId() == null) {
            throw new FieldBusinessException("returnTransactionId", "pharmacy.refund.returnUnreferenced");
        }
        // The sale or the dispense row locked: two refunds of it are counted in turn.
        Source source = sourceOf(ret, actor.hospitalId());
        if (refundRepository.existsByReturnTransactionIdAndStatusIn(ret.getId(), LIVE)) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.refund.alreadyRequested"));
        }
        BigDecimal already = source.saleId() != null
                ? refundRepository.sumForSale(source.saleId(), LIVE)
                : refundRepository.sumForDispense(source.dispenseId(), LIVE);
        BigDecimal refundable = source.paid().subtract(nz(already)).max(BigDecimal.ZERO);
        if (refundable.signum() <= 0) {
            throw new FieldBusinessException("returnTransactionId", "pharmacy.refund.nothingPaid");
        }
        BigDecimal amount = PharmacyListPrice.amount(source.unitPrice(), ret.getQuantity()).min(refundable);
        if (amount.signum() <= 0) {
            throw new FieldBusinessException("returnTransactionId", "pharmacy.refund.nothingPaid");
        }

        PharmacyPaymentMethod method = refundMethod(request, source);
        TillPaymentMethods.requireReceivedAtTill(method, FIELD_METHOD);
        MobileMoneyOperator operator = method == PharmacyPaymentMethod.MOBILE_MONEY
                ? request.getMobileMoneyOperator() : null;
        String reference = trimToNull(request.getReference());
        if (reference != null) {
            requireReference(reference);
            if (method != PharmacyPaymentMethod.CASH) {
                referenceRegistry.requireUnused(actor.hospitalId(), reference, FIELD_REFERENCE);
            }
        }

        PharmacyRefund refund = refundRepository.save(PharmacyRefund.builder()
                .hospitalId(actor.hospitalId())
                .pharmacyId(ret.getInventoryItem().getPharmacy().getId())
                .returnTransactionId(ret.getId())
                .saleId(source.saleId())
                .dispenseId(source.dispenseId())
                .quantity(ret.getQuantity())
                .amount(amount)
                .method(method)
                .mobileMoneyOperator(operator)
                .reference(reference)
                .reason(reason)
                .status(PharmacyRefundStatus.REQUESTED)
                .requestedBy(actor.userId())
                .requestedAt(now())
                .build());
        audit(AuditEventType.PHARMACY_REFUND_REQUESTED, "Pharmacy refund requested", refund);
        return toDto(refund, actor);
    }

    /** Another hospital admin or accountant approves a requested refund (owner decision D-3). */
    @Transactional
    public PharmacyRefundDTO approve(UUID refundId) {
        Actor actor = requireActor(roleValidator::canApproveRefund, "billing.refund.approversOnly");
        PharmacyRefund refund = lock(refundId, actor);
        requireDecidable(refund, actor);
        refund.setStatus(PharmacyRefundStatus.APPROVED);
        refund.setDecidedBy(actor.userId());
        refund.setDecidedAt(now());
        audit(AuditEventType.PHARMACY_REFUND_APPROVED, "Pharmacy refund approved", refund);
        return toDto(refund, actor);
    }

    /** Another hospital admin or accountant rejects a requested refund, with the reason. */
    @Transactional
    public PharmacyRefundDTO reject(UUID refundId, PharmacyRefundReasonRequestDTO body) {
        Actor actor = requireActor(roleValidator::canApproveRefund, "billing.refund.approversOnly");
        PharmacyRefund refund = lock(refundId, actor);
        requireDecidable(refund, actor);
        String reason = trimToNull(body != null ? body.getReason() : null);
        RequiredFields.check().require(reason != null, FIELD_REASON, "billing.refund.rejectReasonRequired").orThrow();
        requireText(reason, FIELD_REASON);
        refund.setStatus(PharmacyRefundStatus.REJECTED);
        refund.setDecidedBy(actor.userId());
        refund.setDecidedAt(now());
        refund.setDecisionReason(reason);
        audit(AuditEventType.PHARMACY_REFUND_REJECTED, "Pharmacy refund rejected", refund);
        return toDto(refund, actor);
    }

    /**
     * The approved refund is handed to the patient: money out of the till now. A mobile-money payout needs its
     * operator and reference, recorded once across every till. A sale whose paid refunds reach its total is REFUNDED.
     */
    @Transactional
    public PharmacyRefundDTO pay(UUID refundId, PharmacyRefundPayRequestDTO body) {
        Actor actor = requireActor(roleValidator::canPayPharmacyRefund, "pharmacy.refund.payersOnly");
        PharmacyRefund refund = lock(refundId, actor);
        if (refund.getStatus() == PharmacyRefundStatus.PAID) {
            return toDto(refund, actor);
        }
        if (refund.getStatus() != PharmacyRefundStatus.APPROVED) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.notApproved"));
        }
        String given = trimToNull(body != null ? body.getReference() : null);
        String reference = given != null ? given : refund.getReference();
        MobileMoneyOperator operator = body != null && body.getMobileMoneyOperator() != null
                ? body.getMobileMoneyOperator() : refund.getMobileMoneyOperator();
        operator = MobileMoneyOperators.require(refund.getMethod(), operator, "mobileMoneyOperator");
        if (refund.getMethod() == PharmacyPaymentMethod.MOBILE_MONEY && reference == null) {
            throw new FieldBusinessException(FIELD_REFERENCE, "billing.refund.referenceRequired");
        }
        if (given != null && !given.equals(refund.getReference())) {
            requireReference(given);
            if (refund.getMethod() != PharmacyPaymentMethod.CASH) {
                referenceRegistry.requireUnused(actor.hospitalId(), given, FIELD_REFERENCE);
            }
        }
        refund.setReference(reference);
        refund.setMobileMoneyOperator(operator);
        refund.setStatus(PharmacyRefundStatus.PAID);
        refund.setPaidBy(actor.userId());
        refund.setPaidAt(now());
        refundRepository.save(refund);
        if (refund.getSaleId() != null) {
            saleRepository.findLockedById(refund.getSaleId()).ifPresent(sale -> {
                BigDecimal paidOut = nz(refundRepository.sumForSale(sale.getId(),
                        EnumSet.of(PharmacyRefundStatus.PAID)));
                if (sale.getTotalAmount() != null && paidOut.compareTo(sale.getTotalAmount()) >= 0) {
                    sale.setStatus(PharmacySaleStatus.REFUNDED);
                }
            });
        }
        audit(AuditEventType.PHARMACY_REFUND_PAID, "Pharmacy refund paid out", refund);
        return toDto(refund, actor);
    }

    /** A refund not yet paid is withdrawn, with a reason, by the person who asked for it or by an approver. */
    @Transactional
    public PharmacyRefundDTO cancel(UUID refundId, PharmacyRefundReasonRequestDTO body) {
        Actor actor = requireActor((user, hospital) -> roleValidator.canRequestPharmacyRefund(user, hospital)
                || roleValidator.canApproveRefund(user, hospital), "pharmacy.refund.requestersOnly");
        PharmacyRefund refund = lock(refundId, actor);
        if (!CANCELLABLE_STATUSES.contains(refund.getStatus())) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.notCancellable"));
        }
        boolean requester = actor.userId().equals(refund.getRequestedBy());
        if (!requester && !roleValidator.canApproveRefund(actor.userId(), actor.hospitalId())) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.refund.cancelRequesterOnly"));
        }
        String reason = trimToNull(body != null ? body.getReason() : null);
        RequiredFields.check().require(reason != null, FIELD_REASON, "billing.refund.cancelReasonRequired").orThrow();
        requireText(reason, FIELD_REASON);
        refund.setStatus(PharmacyRefundStatus.CANCELLED);
        refund.setCancelledBy(actor.userId());
        refund.setCancelledAt(now());
        refund.setCancelReason(reason);
        audit(AuditEventType.PHARMACY_REFUND_CANCELLED, "Pharmacy refund cancelled", refund);
        return toDto(refund, actor);
    }

    // ───────────────────────────── reads ─────────────────────────────

    /** The hospital's refunds in the given states (REQUESTED and APPROVED by default), oldest first. */
    @Transactional(readOnly = true)
    public List<PharmacyRefundDTO> list(Collection<PharmacyRefundStatus> statuses) {
        Actor actor = requireActor((user, hospital) -> roleValidator.canRequestPharmacyRefund(user, hospital)
                || roleValidator.canApproveRefund(user, hospital), "pharmacy.refund.requestersOnly");
        Collection<PharmacyRefundStatus> wanted = statuses == null || statuses.isEmpty()
                ? CANCELLABLE_STATUSES : statuses;
        return refundRepository.findByHospitalIdAndStatusInOrderByCreatedAtAsc(actor.hospitalId(), wanted).stream()
                .map(r -> toDto(r, actor)).toList();
    }

    // ───────────────────────────── internals ─────────────────────────────

    private Actor requireActor(BiPredicate<UUID, UUID> holdsRole, String refusalKey) {
        com.example.hms.security.context.ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService,
                "pharmacy refund");
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID userId = roleValidator.getCurrentUserId();
        if (hospitalId == null || userId == null || !holdsRole.test(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(refusalKey));
        }
        return new Actor(userId, hospitalId);
    }

    private PharmacyRefund lock(UUID refundId, Actor actor) {
        return refundRepository.findLockedByIdAndHospitalId(refundId, actor.hospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private static void requireDecidable(PharmacyRefund refund, Actor actor) {
        if (refund.getStatus() != PharmacyRefundStatus.REQUESTED) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.notRequested"));
        }
        if (actor.userId().equals(refund.getRequestedBy())) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.selfApproval"));
        }
    }

    private static boolean inHospital(InventoryItem item, UUID hospitalId) {
        return item != null && item.getPharmacy() != null && item.getPharmacy().getHospital() != null
                && hospitalId.equals(item.getPharmacy().getHospital().getId());
    }

    /**
     * The sale or the dispense a return gives back, locked, with the price of one returned unit and what the
     * patient paid on it. Another pharmacy's (or hospital's) answers as a return that cannot be refunded.
     */
    private Source sourceOf(StockTransaction ret, UUID hospitalId) {
        if (ret.getReferenceId() == null) {
            throw new FieldBusinessException("returnTransactionId", "pharmacy.refund.returnUnreferenced");
        }
        UUID pharmacyId = ret.getInventoryItem().getPharmacy().getId();
        PharmacySale sale = saleRepository.findLockedById(ret.getReferenceId()).orElse(null);
        if (sale != null) {
            if (sale.getHospital() == null || !hospitalId.equals(sale.getHospital().getId())
                    || sale.getPharmacy() == null || !pharmacyId.equals(sale.getPharmacy().getId())) {
                throw new FieldBusinessException("returnTransactionId", "pharmacy.refund.returnUnreferenced");
            }
            BigDecimal unitPrice = sale.getLines().stream()
                    .filter(line -> sameStock(line, ret))
                    .map(SaleLine::getUnitPrice)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> new FieldBusinessException("returnTransactionId",
                            "pharmacy.refund.returnUnreferenced"));
            return new Source(sale.getId(), null, unitPrice, nz(sale.getTotalAmount()), sale.getPaymentMethod());
        }
        Dispense dispense = dispenseRepository.findLockedById(ret.getReferenceId()).orElse(null);
        if (dispense == null || dispense.getPharmacy() == null || !pharmacyId.equals(dispense.getPharmacy().getId())) {
            throw new FieldBusinessException("returnTransactionId", "pharmacy.refund.returnUnreferenced");
        }
        BigDecimal unitPrice = java.util.Optional.ofNullable(dispense.getMedicationCatalogItem())
                .flatMap(m -> PharmacyListPrice.of(pharmacyId, m.getId(), inventoryItemRepository))
                .orElseThrow(() -> new FieldBusinessException("returnTransactionId", "pharmacy.refund.nothingPaid"));
        BigDecimal paid = nz(paymentRepository.sumPostedForDispense(dispense.getId()));
        PharmacyPaymentMethod paidBy = paymentRepository.findFirstByDispense_IdOrderByCreatedAtAsc(dispense.getId())
                .map(com.example.hms.model.pharmacy.PharmacyPayment::getPaymentMethod).orElse(null);
        return new Source(null, dispense.getId(), unitPrice, paid, paidBy);
    }

    /** How the money goes back: as asked, or the way it was paid. */
    private static PharmacyPaymentMethod refundMethod(PharmacyRefundRequestDTO request, Source source) {
        PharmacyPaymentMethod method = request.getMethod() != null ? request.getMethod() : source.paidBy();
        if (method == null) {
            throw new FieldBusinessException(FIELD_METHOD, "pharmacy.payment.methodRequired");
        }
        return method;
    }

    private static boolean sameStock(SaleLine line, StockTransaction ret) {
        if (ret.getStockLot() != null) {
            return line.getStockLot() != null && ret.getStockLot().getId().equals(line.getStockLot().getId());
        }
        return line.getStockLot() == null && line.getMedicationCatalogItem() != null
                && ret.getInventoryItem().getMedicationCatalogItem() != null
                && line.getMedicationCatalogItem().getId().equals(ret.getInventoryItem().getMedicationCatalogItem().getId());
    }

    private static void requireText(String text, String field) {
        if (text.length() > TEXT_MAX) {
            throw new FieldBusinessException(field, "billing.refund.reasonTooLong");
        }
        CardNumbers.requireNone(text, field);
    }

    private static void requireReference(String reference) {
        if (reference.length() > REFERENCE_MAX) {
            throw new FieldBusinessException(FIELD_REFERENCE, "billing.refund.referenceTooLong");
        }
        CardNumbers.requireNone(reference, FIELD_REFERENCE);
    }

    private void audit(AuditEventType type, String description, PharmacyRefund refund) {
        support.logAudit(type, description, refund.getId().toString(), AUDIT_ENTITY);
    }

    private PharmacyRefundDTO toDto(PharmacyRefund r, Actor actor) {
        String medication = stockTransactionRepository.findById(r.getReturnTransactionId())
                .map(StockTransaction::getInventoryItem)
                .map(InventoryItem::getMedicationCatalogItem)
                .map(m -> m.getNameFr() != null ? m.getNameFr() : m.getGenericName())
                .orElse(null);
        String requester = userRepository.findById(r.getRequestedBy())
                .map(u -> ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                        + (u.getLastName() != null ? u.getLastName() : "")).trim())
                .orElse(null);
        boolean canDecide = r.getStatus() == PharmacyRefundStatus.REQUESTED
                && !actor.userId().equals(r.getRequestedBy())
                && roleValidator.canApproveRefund(actor.userId(), actor.hospitalId());
        return PharmacyRefundDTO.builder()
                .id(r.getId())
                .pharmacyId(r.getPharmacyId())
                .returnTransactionId(r.getReturnTransactionId())
                .saleId(r.getSaleId())
                .dispenseId(r.getDispenseId())
                .medicationName(medication)
                .quantity(r.getQuantity())
                .amount(r.getAmount())
                .method(r.getMethod() != null ? r.getMethod().name() : null)
                .mobileMoneyOperator(r.getMobileMoneyOperator() != null ? r.getMobileMoneyOperator().name() : null)
                .reference(r.getReference())
                .reason(r.getReason())
                .status(r.getStatus().name())
                .requestedBy(r.getRequestedBy())
                .requestedByName(requester)
                .requestedAt(r.getRequestedAt())
                .decidedBy(r.getDecidedBy())
                .decidedAt(r.getDecidedAt())
                .decisionReason(r.getDecisionReason())
                .paidBy(r.getPaidBy())
                .paidAt(r.getPaidAt())
                .cancelReason(r.getCancelReason())
                .canDecide(canDecide)
                .build();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
