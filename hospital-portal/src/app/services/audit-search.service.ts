import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { AuditSearchFilter, AuditSearchPage } from './audit-search.model';

/**
 * MVP-8: Calls /super-admin/audit-search with the provided filter.
 * Empty / undefined fields are dropped so the URL stays clean.
 */
@Injectable({ providedIn: 'root' })
export class AuditSearchService {
  private readonly http = inject(HttpClient);

  search(filter: AuditSearchFilter): Observable<AuditSearchPage> {
    let params = new HttpParams();
    const set = (key: string, value: string | number | undefined) => {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    };

    set('userId', filter.userId);
    set('userName', filter.userName);
    set('status', filter.status);
    set('hospitalId', filter.hospitalId);
    set('organizationId', filter.organizationId);
    set('impersonatorUserId', filter.impersonatorUserId);
    set('entityType', filter.entityType);
    set('resourceId', filter.resourceId);
    set('fromDate', filter.fromDate);
    set('toDate', filter.toDate);
    // MVP-9b — region scope passed through as the backend OrganizationRegion enum name.
    set('tenantRegion', filter.tenantRegion);
    set('page', filter.page);
    set('size', filter.size);

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
    let params = new HttpParams();
    const set = (key: string, value: string | number | undefined) => {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    };

    set('userId', filter.userId);
    set('userName', filter.userName);
    set('status', filter.status);
    set('hospitalId', filter.hospitalId);
    set('organizationId', filter.organizationId);
    set('impersonatorUserId', filter.impersonatorUserId);
    set('entityType', filter.entityType);
    set('resourceId', filter.resourceId);
    set('fromDate', filter.fromDate);
    set('toDate', filter.toDate);
    set('tenantRegion', filter.tenantRegion);
    set('maxRows', maxRows);

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
}
