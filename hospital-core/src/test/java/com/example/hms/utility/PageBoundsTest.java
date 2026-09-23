package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page cap that bounds how many cross-hospital disclosures one request
 * can write. Capping the size while keeping the page NUMBER silently moved
 * the window, which is the defect these cases pin.
 */
class PageBoundsTest {

    @Test
    @DisplayName("a page within the cap is returned untouched")
    void withinTheCapIsUnchanged() {
        Pageable requested = PageRequest.of(3, 100, Sort.by("orderDatetime"));

        assertThat(PageBounds.atMost(requested, 500)).isSameAs(requested);
    }

    @Test
    @DisplayName("an oversized page keeps its starting row, not its page number")
    void oversizedPageKeepsItsOffset() {
        // Page 2 of 1000 starts at row 2000. Keeping the number and shrinking
        // the size would have started at 1000 — a different slice, rows seen
        // twice while paging, and a tail nobody can reach.
        Pageable bounded = PageBounds.atMost(PageRequest.of(2, 1000), 500);

        assertThat(bounded.getPageSize()).isEqualTo(500);
        assertThat(bounded.getOffset()).isEqualTo(2000);
    }

    @Test
    @DisplayName("the first oversized page still starts at the first row")
    void firstPageStaysAtTheStart() {
        Pageable bounded = PageBounds.atMost(PageRequest.of(0, 100_000), 500);

        assertThat(bounded.getPageSize()).isEqualTo(500);
        assertThat(bounded.getOffset()).isZero();
    }

    @Test
    @DisplayName("an offset that is not a multiple of the cap falls back to the boundary at or before it")
    void unexpressibleOffsetFallsBackEarlier() {
        // Offset 750 cannot be a page of 500; landing on 500 shows rows
        // earlier than asked for, never fewer.
        Pageable bounded = PageBounds.atMost(PageRequest.of(1, 750), 500);

        assertThat(bounded.getOffset()).isEqualTo(500);
        assertThat(bounded.getOffset()).isLessThanOrEqualTo(750);
    }

    @Test
    @DisplayName("an unpaged request becomes the first capped page instead of throwing")
    void unpagedIsBounded() {
        // Pageable.unpaged().getPageSize() throws, so it has to be checked first.
        Pageable bounded = PageBounds.atMost(Pageable.unpaged(), 500);

        assertThat(bounded.isPaged()).isTrue();
        assertThat(bounded.getPageSize()).isEqualTo(500);
        assertThat(bounded.getOffset()).isZero();
    }

    @Test
    @DisplayName("a null pageable is bounded rather than propagated")
    void nullIsBounded() {
        assertThat(PageBounds.atMost(null, 500).getPageSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("the sort survives the cap")
    void sortIsPreserved() {
        Sort sort = Sort.by(Sort.Direction.DESC, "resultDate");

        assertThat(PageBounds.atMost(PageRequest.of(1, 900, sort), 500).getSort()).isEqualTo(sort);
    }
}
