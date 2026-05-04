package com.example.hms.service;

import com.example.hms.enums.AuditSource;
import com.example.hms.payload.dto.superadmin.AggregatedAuditPageDTO;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * MVP-8c — cross-source audit aggregation. Materialises rows from
 * each requested source separately, merges them by timestamp DESC,
 * and paginates the merged stream. Used by the super-admin
 * audit-search UI's "All sources" tab.
 */
public interface SuperAdminAuditAggregationService {

    /**
     * @param sources  one or more sources to include; empty/null means
     *                 "all three". Filter applied server-side so a
     *                 caller cannot widen the result by spoofing the
     *                 query.
     * @param fromDate inclusive lower bound on event timestamp; null
     *                 means "no lower bound" (the per-source LIMIT
     *                 still caps work).
     * @param toDate   inclusive upper bound; null means "no upper bound".
     * @param pageable page coordinates; sort is ignored — the merge is
     *                 always timestamp DESC.
     */
    AggregatedAuditPageDTO searchAggregated(
        Set<AuditSource> sources,
        LocalDateTime fromDate,
        LocalDateTime toDate,
        Pageable pageable);
}
