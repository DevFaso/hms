import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Row-44 v2 chargeback row — mirrors the backend
 * {@code TenantCostRowV2} record. Stable {@code hospitalId} key plus
 * counts the cost model uses to derive the chargeback amount; the
 * Splunk / Grafana / storage inputs are zero in the foundation pass
 * (the row-44 cell text names those as follow-on inputs that need
 * external exporters or the row-33 schema-per-tenant landing).
 */
export interface ChargebackRow {
  hospitalId: string;
  hospitalName: string;
  auditEventCount: number;
  splunkEventCount: number;
  grafanaSeriesCardinality: number;
  postgresStorageBytes: number;
  chargebackAmount: number;
  currency: string;
}

@Injectable({ providedIn: 'root' })
export class ChargebackService {
  private readonly http = inject(HttpClient);

  /**
   * GET /api/super-admin/cost/per-tenant/chargeback?from&to. Inclusive
   * date window; absent params default server-side to the trailing 30
   * days. Server caps the window at 92 days.
   */
  perTenant(from?: string, to?: string): Observable<ChargebackRow[]> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<ChargebackRow[]>('/super-admin/cost/per-tenant/chargeback', { params });
  }
}
