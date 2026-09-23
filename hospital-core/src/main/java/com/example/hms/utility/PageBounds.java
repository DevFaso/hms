package com.example.hms.utility;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Ceiling on how wide a page a caller may ask for.
 *
 * <p>{@code @PageableDefault(size = …)} is a default, not a bound: a caller
 * may ask for any size. On the reads that account a cross-hospital disclosure
 * per surfaced row, an unbounded page is an unbounded number of audit rows
 * written inside the request, so the page is capped and the accounting is
 * capped with it.
 */
public final class PageBounds {

    private PageBounds() {
    }

    /**
     * The requested window, never wider than {@code maxSize}.
     *
     * <p>Capping the size while keeping the page NUMBER would silently move
     * the window: page 2 of 1000 starts at row 2000, page 2 of 500 at row
     * 1000, so the caller gets a different slice, sees rows twice as they
     * page, and can never reach the tail. The offset is what the caller
     * asked for, so the offset is what is preserved: the page number is
     * recomputed against the capped size.
     *
     * <p>An offset that is not a multiple of the capped size cannot be
     * expressed as a page, so it falls back to the boundary at or before it —
     * the caller then sees rows earlier than requested, never fewer than
     * requested. An unpaged request is unbounded by definition and becomes
     * the first capped page; {@code getPageSize()} throws on it, which is why
     * it is checked first.
     */
    public static Pageable atMost(Pageable pageable, int maxSize) {
        if (pageable == null) {
            return PageRequest.of(0, maxSize);
        }
        if (pageable.isUnpaged()) {
            return PageRequest.of(0, maxSize);
        }
        if (pageable.getPageSize() <= maxSize) {
            return pageable;
        }
        long boundedPageNumber = pageable.getOffset() / maxSize;
        return PageRequest.of((int) boundedPageNumber, maxSize, pageable.getSort());
    }
}
