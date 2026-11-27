package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Insurer claims plan PR 1 (V383, QA D3984): the insurer's pre-authorisation number of an act on its invoice lines.
 *
 * <ul>
 *   <li>{@link #normalise} is the one rule for a typed number (the line, the imaging order's desk and clinician
 *       paths): trimmed, not blank, 120 characters at most, letters, digits, spaces and {@code . / _ # : -}, and
 *       never a payment-card number.</li>
 *   <li>{@link #copyFromOrder} copies a number recorded on an order onto every line that order was billed on at its
 *       hospital, on invoices not cancelled, whether the invoice is a draft or already issued.</li>
 * </ul>
 *
 * <p>A plain UPDATE: the line's amounts and its payer share are never touched. Audited after commit with ids and the
 * number of lines only (the number itself is not written to the audit row).
 */
@Slf4j
@Component
public class InvoiceLinePreAuthorisations {

    /** The request field the errors name. */
    public static final String FIELD = "authorizationNumber";
    public static final int MAX_LENGTH = 120;
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{N} ./_#:-]+");
    private static final String AUDIT_ENTITY = "BILLING_INVOICE";

    private final InvoiceItemRepository itemRepository;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    public InvoiceLinePreAuthorisations(InvoiceItemRepository itemRepository,
                                        AuditEventLogService auditEventLogService, Clock clock) {
        this.itemRepository = itemRepository;
        this.auditEventLogService = auditEventLogService;
        this.clock = clock;
    }

    /**
     * The number as stored: trimmed, inner runs of spaces folded.
     *
     * @throws FieldBusinessException when it is blank, too long or holds another character
     */
    public static String normalise(String raw, String field) {
        String number = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (number.isEmpty()) {
            throw new FieldBusinessException(field, "billing.preAuthorisation.required");
        }
        if (number.length() > MAX_LENGTH) {
            throw new FieldBusinessException(field, "billing.preAuthorisation.tooLong");
        }
        if (!ALLOWED.matcher(number).matches()) {
            throw new FieldBusinessException(field, "billing.preAuthorisation.characters");
        }
        // D-15: digits, spaces and dashes are allowed, so a payment-card number would pass the pattern; it is refused
        // here, never stored, never printed (the mailed invoice included).
        com.example.hms.utility.CardNumbers.requireNone(number, field);
        return number;
    }

    /**
     * Copies {@code number} onto the lines {@code orderId} was billed on at {@code hospitalId} (invoices not
     * cancelled), stamped with {@code actorId} and now. Returns how many lines received it (0: none billed yet; the
     * checkout copies it when the visit is billed).
     */
    public int copyFromOrder(BillableOrderType type, UUID orderId, UUID hospitalId, String number, UUID actorId) {
        if (type == null || orderId == null || hospitalId == null || number == null || number.isBlank()) {
            return 0;
        }
        List<UUID> lines = itemRepository.findLiveLineIdsOfOrder(type.name(), orderId, hospitalId);
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int written = itemRepository.recordPreAuthorisation(lines, hospitalId, number, actorId,
            LocalDateTime.now(clock));
        audit(actorId, hospitalId, null, "Pre-authorisation copied from order " + orderId + " to " + written
            + " invoice line(s)", String.valueOf(orderId));
        return written;
    }

    /** One line written by hand: audited with its line and invoice ids. */
    void auditLine(UUID actorId, UUID hospitalId, UUID invoiceId, UUID itemId) {
        audit(actorId, hospitalId, invoiceId, "Pre-authorisation recorded on line " + itemId + " of invoice "
            + invoiceId, String.valueOf(invoiceId));
    }

    private void audit(UUID actorId, UUID hospitalId, UUID invoiceId, String description, String resourceId) {
        if (auditEventLogService == null) {
            return;
        }
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.PRE_AUTHORISATION_RECORDED)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(invoiceId != null ? AUDIT_ENTITY : "IMAGING_ORDER")
                    .resourceId(resourceId)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Pre-authorisation audit not written for {}: {}", resourceId, ex.getClass().getSimpleName());
            }
        });
    }
}
