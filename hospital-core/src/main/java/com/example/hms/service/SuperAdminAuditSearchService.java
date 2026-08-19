package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import org.springframework.data.domain.Pageable;

/**
 * MVP-8: Cross-tenant audit search for super admins. Builds a JPA
 * Specification from the optional filters and delegates to
 * {@link com.example.hms.repository.AuditEventLogRepository}.
 *
 * <p>Surfaces the impersonator columns introduced by MVP-4 so a forensic
 * search can find every action taken under an impersonation token.
 */
public interface SuperAdminAuditSearchService {

    /**
     * Run a paged search across {@code AuditEventLog}. Filters are
     * supplied as a single parameter object — see {@link AuditSearchFilter}.
     */
    AuditSearchPageDTO search(AuditSearchFilter filter, Pageable pageable);

    /**
     * MVP-8b: render the same filter as a CSV byte stream for download.
     * Hard-capped at {@code maxRows} so a runaway export can't OOM the
     * service (the caller — controller — picks a sane ceiling, e.g.
     * 10 000 rows).
     */
    byte[] exportCsv(AuditSearchFilter filter, int maxRows);
}
