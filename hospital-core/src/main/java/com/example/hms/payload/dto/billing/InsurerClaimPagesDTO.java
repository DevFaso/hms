package com.example.hms.payload.dto.billing;

import java.util.List;

/**
 * Insurer claims plan PRs 2 and 3: one page of bordereaux and one page of insurer payments. {@code page} is
 * zero-based.
 */
public final class InsurerClaimPagesDTO {

    private InsurerClaimPagesDTO() {
    }

    /** One page of the hospital's bordereaux, newest first. */
    public record Batches(List<InsurerClaimBatchDTO> content, int page, int size, long totalElements,
                          int totalPages) {
    }

    /** One page of the hospital's insurer payments, the latest received first. */
    public record Payments(List<InsurerPaymentDTO> content, int page, int size, long totalElements, int totalPages) {
    }
}
