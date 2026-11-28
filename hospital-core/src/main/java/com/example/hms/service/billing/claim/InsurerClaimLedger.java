package com.example.hms.service.billing.claim;

import com.example.hms.enums.InsurerClaimBatchStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.InsurerClaimBatch;
import com.example.hms.model.InsurerClaimLine;
import com.example.hms.model.InvoicePayerShare;
import com.example.hms.repository.InsurerClaimBatchRepository;
import com.example.hms.repository.InsurerClaimLineRepository;
import com.example.hms.repository.InvoicePayerShareRepository;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (plan section 4.7): the one place the settlement amounts of a bordereau line and of its
 * payer share move, so the two always agree.
 *
 * <p><b>Locks.</b> Every settlement step first takes, in this order and each in id order, the row locks of the
 * bordereaux, then of the payer shares, then of the lines it touches, and holds them to the end of the transaction.
 * The lock on the bordereau alone serialises everything settled on it; two people allocating to the same line at
 * once cannot overpay it. The entities are read for the first time under their lock, so their amounts are the
 * stored ones.
 *
 * <p><b>Conservation.</b> A line never closes more than it claims
 * ({@code paid + re-billed + written off + held by a write-off request <= claimed}) and a share never closes more
 * than its amount; each step is refused before it would, and the schema's CHECKs refuse it again.
 */
@Component
@RequiredArgsConstructor
public class InsurerClaimLedger {

    static final String LINE_NOT_FOUND = "billing.claim.lineNotFound";

    private final InsurerClaimBatchRepository batchRepository;
    private final InsurerClaimLineRepository lineRepository;
    private final InvoicePayerShareRepository shareRepository;

    /** The bordereaux, shares and lines of one settlement step, all under their row locks. */
    public record Locked(Map<UUID, InsurerClaimBatch> batches, Map<UUID, InvoicePayerShare> shares,
                         Map<UUID, InsurerClaimLine> lines) {

        public InsurerClaimLine line(UUID lineId) {
            return lines.get(lineId);
        }

        public InvoicePayerShare shareOf(InsurerClaimLine line) {
            return shares.get(line.getShareId());
        }

        public InsurerClaimBatch batchOf(InsurerClaimLine line) {
            return batches.get(line.getBatchId());
        }
    }

    /**
     * Locks these lines with their bordereaux and shares at one hospital. A line that does not exist there, or
     * whose bordereau was cancelled, answers as a missing one; a line of a bordereau not sent yet, or already
     * closed to changes, is refused.
     */
    public Locked lock(Collection<UUID> lineIds, UUID hospitalId) {
        Set<UUID> wanted = new HashSet<>(lineIds);
        List<InsurerClaimLineRepository.Ref> refs = lineRepository.refsAtHospital(wanted, hospitalId);
        if (refs.size() != wanted.size()) {
            throw new ResourceNotFoundException(LINE_NOT_FOUND);
        }
        Set<UUID> batchIds = new TreeSet<>();
        Set<UUID> shareIds = new TreeSet<>();
        for (InsurerClaimLineRepository.Ref ref : refs) {
            batchIds.add(ref.batchId());
            shareIds.add(ref.shareId());
        }
        Map<UUID, InsurerClaimBatch> batches = new HashMap<>();
        for (InsurerClaimBatch batch : batchRepository.lockAllByIdInAndHospitalId(batchIds, hospitalId)) {
            batches.put(batch.getId(), batch);
        }
        Map<UUID, InvoicePayerShare> shares = new HashMap<>();
        for (InvoicePayerShare share : shareRepository.lockAllByIdInAndHospitalId(shareIds, hospitalId)) {
            shares.put(share.getId(), share);
        }
        Map<UUID, InsurerClaimLine> lines = new HashMap<>();
        for (InsurerClaimLine line : lineRepository.lockAllByIdInAndHospitalId(new TreeSet<>(wanted), hospitalId)) {
            lines.put(line.getId(), line);
        }
        for (InsurerClaimLine line : lines.values()) {
            InsurerClaimBatch batch = batches.get(line.getBatchId());
            // Read again under the lock: the bordereau may have been cancelled since the ids were read.
            if (line.isReleased() || batch == null || !shares.containsKey(line.getShareId())) {
                throw new ResourceNotFoundException(LINE_NOT_FOUND);
            }
            if (!batch.getStatus().isSent()) {
                throw new ConflictException(MessageUtil.resolve("billing.claim.notSubmitted"));
            }
        }
        if (lines.size() != wanted.size()) {
            throw new ResourceNotFoundException(LINE_NOT_FOUND);
        }
        return new Locked(batches, shares, lines);
    }

    /** What the line can still take: a payment, a refusal or a new write-off request above it is refused. */
    public static boolean fits(InsurerClaimLine line, BigDecimal amount) {
        return amount.compareTo(line.availableAmount()) <= 0;
    }

    /** The payer paid {@code amount} of the line. */
    public void pay(InsurerClaimLine line, InvoicePayerShare share, BigDecimal amount) {
        requireFits(line, amount);
        line.setPaidAmount(line.getPaidAmount().add(amount));
        share.setPaidAmount(share.getPaidAmount().add(amount));
        requireConserved(share);
    }

    /** A payment of {@code amount} on the line is undone (its payment was reversed). */
    public void unpay(InsurerClaimLine line, InvoicePayerShare share, BigDecimal amount) {
        if (amount.compareTo(line.getPaidAmount()) > 0 || amount.compareTo(share.getPaidAmount()) > 0) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.changedMeanwhile"));
        }
        line.setPaidAmount(line.getPaidAmount().subtract(amount));
        share.setPaidAmount(share.getPaidAmount().subtract(amount));
    }

    /** The payer refused {@code amount} of the line and the hospital re-bills it to the patient. */
    public void rebill(InsurerClaimLine line, InvoicePayerShare share, BigDecimal amount) {
        requireFits(line, amount);
        line.setRebilledAmount(line.getRebilledAmount().add(amount));
        share.setRejectedAmount(share.getRejectedAmount().add(amount));
        requireConserved(share);
    }

    /** A write-off request holds {@code amount} of the line until it is decided. */
    public void hold(InsurerClaimLine line, BigDecimal amount) {
        requireFits(line, amount);
        line.setWriteOffPendingAmount(line.getWriteOffPendingAmount().add(amount));
    }

    /** A write-off request no longer holds {@code amount} of the line (decided, or cancelled). */
    public void release(InsurerClaimLine line, BigDecimal amount) {
        if (amount.compareTo(line.getWriteOffPendingAmount()) > 0) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.changedMeanwhile"));
        }
        line.setWriteOffPendingAmount(line.getWriteOffPendingAmount().subtract(amount));
    }

    /** An approved write-off closes {@code amount} of the line. The patient's share of the invoice is untouched. */
    public void writeOff(InsurerClaimLine line, InvoicePayerShare share, BigDecimal amount) {
        requireFits(line, amount);
        line.setWrittenOffAmount(line.getWrittenOffAmount().add(amount));
        share.setWrittenOffAmount(share.getWrittenOffAmount().add(amount));
        requireConserved(share);
    }

    /**
     * After the amounts moved: each share takes the status its amounts give it, and each bordereau is SUBMITTED
     * while nothing is closed on it, PARTIALLY_SETTLED once something is, SETTLED when every line is closed.
     */
    public void restate(Locked locked, UUID actorId, LocalDateTime at) {
        for (InvoicePayerShare share : locked.shares().values()) {
            share.setStatus(share.settledStatus());
            share.setDecidedBy(actorId);
            share.setDecidedAt(at);
        }
        shareRepository.saveAll(locked.shares().values());
        lineRepository.saveAll(locked.lines().values());
        lineRepository.flush();
        for (InsurerClaimBatch batch : locked.batches().values()) {
            batch.setStatus(statusOf(lineRepository.findByBatchIdAndHospitalId(batch.getId(),
                batch.getHospitalId())));
        }
        batchRepository.saveAll(locked.batches().values());
    }

    /** The status a sent bordereau takes from its lines. */
    static InsurerClaimBatchStatus statusOf(List<InsurerClaimLine> lines) {
        boolean anyClosed = false;
        boolean anyOutstanding = false;
        for (InsurerClaimLine line : lines) {
            BigDecimal outstanding = line.outstandingAmount();
            if (outstanding.signum() > 0) {
                anyOutstanding = true;
            }
            if (outstanding.compareTo(line.getClaimedAmount()) < 0) {
                anyClosed = true;
            }
        }
        if (!anyOutstanding && !lines.isEmpty()) {
            return InsurerClaimBatchStatus.SETTLED;
        }
        return anyClosed ? InsurerClaimBatchStatus.PARTIALLY_SETTLED : InsurerClaimBatchStatus.SUBMITTED;
    }

    private static void requireFits(InsurerClaimLine line, BigDecimal amount) {
        if (!fits(line, amount)) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.exceedsLine",
                InsurerClaimAccess.francs(line.availableAmount().max(BigDecimal.ZERO))));
        }
    }

    /** A share whose lines closed more than its amount would be a broken ledger: refused, nothing is written. */
    private static void requireConserved(InvoicePayerShare share) {
        if (share.openAmount().signum() < 0) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.changedMeanwhile"));
        }
    }
}
