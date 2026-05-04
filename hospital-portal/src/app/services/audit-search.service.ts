import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  AggregatedAuditFilter,
  AggregatedAuditPage,
  AuditSearchFilter,
  AuditSearchPage,
} from './audit-search.model';

/** Scalar value an HttpParams entry can carry once stringified. */
type ParamValue = string | number | undefined;

/**
 * Builds an `HttpParams` instance from a flat map. Drops null,
 * undefined, and empty-string entries so the URL stays clean. Keeps
 * the per-method body short — extracted from three near-identical
 * inline helpers (Sonar S4144).
 */
function buildParams(entries: Record<string, ParamValue>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(entries)) {
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

/**
 * MVP-8: Calls /super-admin/audit-search with the provided filter.
 * Empty / undefined fields are dropped so the URL stays clean.
 */
@Injectable({ providedIn: 'root' })
export class AuditSearchService {
  private readonly http = inject(HttpClient);

  search(filter: AuditSearchFilter): Observable<AuditSearchPage> {
    let params = buildParams({
      userId: filter.userId,
      userName: filter.userName,
      status: filter.status,
      hospitalId: filter.hospitalId,
      organizationId: filter.organizationId,
      impersonatorUserId: filter.impersonatorUserId,
      entityType: filter.entityType,
      resourceId: filter.resourceId,
      fromDate: filter.fromDate,
      toDate: filter.toDate,
      // MVP-9b — region scope passed through as the backend OrganizationRegion enum name.
      tenantRegion: filter.tenantRegion,
      page: filter.page,
      size: filter.size,
    });

    if (filter.eventTypes && filter.eventTypes.length > 0) {
      for (const t of filter.eventTypes) {
        params = params.append('eventTypes', t);
      }
    }

    return this.http.get<AuditSearchPage>('/api/super-admin/audit-search', { params });
  }

  /**
   * MVP-8b — same filter shape as `search()` but returns the response
   * as a Blob so the component can trigger a browser download.
   */
  exportCsv(filter: AuditSearchFilter, maxRows = 10_000): Observable<Blob> {
    let params = buildParams({
      userId: filter.userId,
      userName: filter.userName,
      status: filter.status,
      hospitalId: filter.hospitalId,
      organizationId: filter.organizationId,
      impersonatorUserId: filter.impersonatorUserId,
      entityType: filter.entityType,
      resourceId: filter.resourceId,
      fromDate: filter.fromDate,
      toDate: filter.toDate,
      tenantRegion: filter.tenantRegion,
      maxRows,
    });

    if (filter.eventTypes && filter.eventTypes.length > 0) {
      for (const t of filter.eventTypes) {
        params = params.append('eventTypes', t);
      }
    }

    return this.http.get('/api/super-admin/audit-search/csv', {
      params,
      responseType: 'blob',
    });
  }

  /**
   * MVP-8c — calls the cross-source aggregation endpoint that unions
   * `audit_event_logs`, `frontend_audit_events`, and
   * `permission_matrix_audit_events`. Empty `sources` defaults to all
   * three on the backend; the per-source row cap (5 000) is enforced
   * server-side so a deep-page request stays bounded.
   */
  searchAggregated(filter: AggregatedAuditFilter): Observable<AggregatedAuditPage> {
    let params = buildParams({
      fromDate: filter.fromDate,
      toDate: filter.toDate,
      page: filter.page,
      size: filter.size,
    });

    if (filter.sources && filter.sources.length > 0) {
      for (const source of filter.sources) {
        params = params.append('sources', source);
      }
    }

    return this.http.get<AggregatedAuditPage>('/api/super-admin/audit-search/aggregated', {
      params,
    });
  }
}
