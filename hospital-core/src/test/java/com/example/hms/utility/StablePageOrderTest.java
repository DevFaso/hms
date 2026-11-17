package com.example.hms.utility;

import com.example.hms.model.ImagingReportAttachment;
import com.example.hms.model.LabResult;
import com.example.hms.model.platform.RegionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

/** QA D1361: every paged repository read ends its order on the unique key. */
@DisplayName("QA D1361: StablePageOrder closes every page's order with the unique key")
class StablePageOrderTest {

    @SuppressWarnings("unused")
    interface Shapes {
        void findByStatus(String status, Pageable pageable);

        void findByStatusOrderByResultDateDesc(String status, Pageable pageable);

        @Query("SELECT r FROM LabResult r WHERE r.released = true")
        void unordered(Pageable pageable);

        @Query("SELECT r FROM LabResult r ORDER BY r.resultDate DESC")
        void ordered(Pageable pageable);

        @Query("SELECT DISTINCT p.id, p.lastName FROM Patient p ORDER BY p.id")
        void endsOnId(Pageable pageable);

        @Query("SELECT o FROM LabOrder o")
        void otherRoot(Pageable pageable);

        @Query(value = "SELECT * FROM t ORDER BY sent_at", nativeQuery = true)
        void nativeSql(Pageable pageable);
    }

    private static Method shape(String name) throws NoSuchMethodException {
        for (Method m : Shapes.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Sort sortOf(Pageable page, Class<?> domain, String method) throws NoSuchMethodException {
        return StablePageOrder.forQuery(page, domain, shape(method)).getSort();
    }

    @Test
    void aCallersSortIsKeptAndClosedByIdInItsLastDirection() throws Exception {
        assertThat(sortOf(PageRequest.of(2, 20, Sort.by(DESC, "resultDate")), LabResult.class, "findByStatus"))
            .isEqualTo(Sort.by(DESC, "resultDate").and(Sort.by(DESC, "id")));
        assertThat(sortOf(PageRequest.of(0, 20, Sort.by(DESC, "a").and(Sort.by(ASC, "b"))), LabResult.class, "ordered"))
            .isEqualTo(Sort.by(DESC, "a").and(Sort.by(ASC, "b")).and(Sort.by(ASC, "id")));
    }

    @Test
    void aSortThatNamesIdIsLeftAlone() throws Exception {
        Pageable page = PageRequest.of(1, 5, Sort.by(DESC, "createdAt", "id"));
        assertThat(StablePageOrder.forQuery(page, LabResult.class, shape("findByStatus"))).isSameAs(page);
    }

    @Test
    void noSortAndAnOwnOrderGetsIdAsItsTiebreakOnly() throws Exception {
        assertThat(sortOf(PageRequest.of(1, 5), LabResult.class, "ordered")).isEqualTo(Sort.by(ASC, "id"));
        assertThat(sortOf(PageRequest.of(1, 5), LabResult.class, "findByStatusOrderByResultDateDesc"))
            .isEqualTo(Sort.by(ASC, "id"));
    }

    @Test
    void noSortAndNoOrderIsNewestFirst() throws Exception {
        Sort newest = Sort.by(DESC, "createdAt").and(Sort.by(DESC, "id"));
        assertThat(sortOf(PageRequest.of(1, 5), LabResult.class, "findByStatus")).isEqualTo(newest);
        assertThat(sortOf(PageRequest.of(1, 5), LabResult.class, "unordered")).isEqualTo(newest);
    }

    @Test
    void aQueryOnAnotherRootGetsIdOnly() throws Exception {
        assertThat(sortOf(PageRequest.of(1, 5), LabResult.class, "otherRoot")).isEqualTo(Sort.by(ASC, "id"));
    }

    @Test
    void anEntityWithoutAnIdIsClosedByItsWholeKey() throws Exception {
        assertThat(sortOf(PageRequest.of(1, 5), RegionPolicy.class, "findByStatus")).isEqualTo(Sort.by(ASC, "region"));
        assertThat(StablePageOrder.uniqueKeys(ImagingReportAttachment.class)).containsExactlyInAnyOrder("report", "position");
    }

    @Test
    void anOrderAlreadyTotalAnUnpagedRequestAndNativeSqlAreLeftAlone() throws Exception {
        Pageable page = PageRequest.of(1, 5);
        assertThat(StablePageOrder.forQuery(page, LabResult.class, shape("endsOnId"))).isSameAs(page);
        assertThat(StablePageOrder.forQuery(page, LabResult.class, shape("nativeSql"))).isSameAs(page);
        assertThat(StablePageOrder.forQuery(Pageable.unpaged(), LabResult.class, shape("findByStatus")))
            .isEqualTo(Pageable.unpaged());
        assertThat(StablePageOrder.forQuery(null, LabResult.class, shape("findByStatus"))).isNull();
    }

    @Test
    void thePageWindowIsKept() throws Exception {
        Pageable stable = StablePageOrder.forQuery(PageRequest.of(3, 50), LabResult.class, shape("findByStatus"));
        assertThat(stable.getPageNumber()).isEqualTo(3);
        assertThat(stable.getPageSize()).isEqualTo(50);
    }
}
