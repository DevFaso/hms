package com.example.hms.service.billing.claim;

import com.example.hms.enums.InsurerClaimBatchStatus;
import com.example.hms.model.InsurerClaimBatch;
import com.example.hms.model.InsurerClaimLine;
import com.example.hms.model.InvoicePayerShare;
import com.example.hms.model.InvoicePayerShareLine;
import com.example.hms.repository.InsurerClaimBatchRepository;
import com.example.hms.repository.InsurerClaimLineRepository;
import com.example.hms.repository.InvoicePayerShareLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PR 2 (plan section 4.8, "credit note / invoice cancel"): a draft bordereau follows the payer
 * shares it holds. While a share is OPEN the invoice can still change it: a credit note replaces it or moves what
 * it claims of each line, a cancelled invoice or a whole refusal ends it. The coverage service calls this, under the
 * invoice's row lock, each time it does, and the draft's lines are rewritten from the share as it now stands (or
 * dropped). A share on a bordereau already sent is never touched here: its lines are frozen.
 *
 * <p>The draft is locked first, so a submission running at the same moment either sees the lines as rewritten or
 * finishes first, in which case the share is no longer OPEN and the invoice change is refused by its own check.
 */
@Component
@RequiredArgsConstructor
public class InsurerClaimDraftSync {

    private final InsurerClaimBatchRepository batchRepository;
    private final InsurerClaimLineRepository lineRepository;
    private final InvoicePayerShareLineRepository shareLineRepository;

    /**
     * {@code previousShareId} was replaced by {@code current} (the same share when only its split moved; null when
     * nothing replaces it): every draft holding the previous share now holds the current one's acts instead.
     */
    public void follow(UUID previousShareId, InvoicePayerShare current, UUID hospitalId) {
        if (previousShareId == null || hospitalId == null) {
            return;
        }
        for (UUID batchId : lineRepository.liveBatchIdsOfShare(previousShareId, hospitalId)) {
            InsurerClaimBatch batch = batchRepository.lockByIdAndHospitalId(batchId, hospitalId).orElse(null);
            if (batch == null || batch.getStatus() != InsurerClaimBatchStatus.DRAFT) {
                continue;
            }
            lineRepository.deleteShareFromBatch(batchId, previousShareId, hospitalId);
            if (current != null && current.getId() != null) {
                List<InsurerClaimLine> lines = draftLines(batch, current,
                    shareLineRepository.findByShareIdAndHospitalId(current.getId(), hospitalId));
                if (!lines.isEmpty()) {
                    lineRepository.saveAllAndFlush(lines);
                }
            }
        }
    }

    /**
     * Review of #1394 (F1): the bordereaux, not cancelled, that hold any of these shares, under row locks in id
     * order. The coverage service takes them before it locks or writes the shares, the order a bordereau is sent in
     * (bordereau, then share): a credit note and a sending of the same share wait on each other instead of
     * deadlocking, and the second one reads what the first one left.
     */
    public void lockBatchesOf(Collection<UUID> shareIds, UUID hospitalId) {
        if (shareIds == null || shareIds.isEmpty() || hospitalId == null) {
            return;
        }
        List<UUID> batchIds = lineRepository.liveBatchIdsOfShares(shareIds, hospitalId);
        if (!batchIds.isEmpty()) {
            batchRepository.lockAllByIdInAndHospitalId(batchIds, hospitalId);
        }
    }

    /** The draft lines of one share on one bordereau: one per act the share claims. */
    static List<InsurerClaimLine> draftLines(InsurerClaimBatch batch, InvoicePayerShare share,
                                             List<InvoicePayerShareLine> claimed) {
        List<InsurerClaimLine> lines = new ArrayList<>();
        for (InvoicePayerShareLine row : claimed) {
            if (row.getAmount() == null || row.getAmount().signum() <= 0) {
                continue;
            }
            lines.add(InsurerClaimLine.builder()
                .hospitalId(batch.getHospitalId())
                .batchId(batch.getId())
                .shareId(share.getId())
                .invoiceId(share.getInvoiceId())
                .invoiceItemId(row.getInvoiceItemId())
                .claimedAmount(row.getAmount())
                .build());
        }
        return lines;
    }
}
