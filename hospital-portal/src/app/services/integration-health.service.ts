import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  IntegrationHealthRow,
  IntegrationHealthSummary,
  IntegrationHistoryBucket,
  IntegrationProbeResult,
} from './integration-health.model';

@Injectable({ providedIn: 'root' })
export class IntegrationHealthService {
  private readonly http = inject(HttpClient);

  getInventory(): Observable<IntegrationHealthSummary> {
    return this.http.get<IntegrationHealthSummary>('/super-admin/integrations');
  }

  getIntegration(integrationId: string): Observable<IntegrationHealthRow> {
    return this.http.get<IntegrationHealthRow>(
      `/super-admin/integrations/${encodeURIComponent(integrationId)}`,
    );
  }

  /**
   * MVP-3b — synchronous connectivity probe. Backend records the
   * outcome through the existing recorder, then returns the result so
   * the UI can paint a status pill without a follow-up GET.
   */
  probe(integrationId: string): Observable<IntegrationProbeResult> {
    return this.http.post<IntegrationProbeResult>(
      `/super-admin/integrations/${encodeURIComponent(integrationId)}/probe`,
      {},
    );
  }

  /**
   * MVP-3b — fire-and-forget re-sync. Backend dispatches the work
   * asynchronously and the recorder captures success / failure as the
   * job completes; the immediate response is just an ACK.
   */
  resync(integrationId: string): Observable<IntegrationProbeResult> {
    return this.http.post<IntegrationProbeResult>(
      `/super-admin/integrations/${encodeURIComponent(integrationId)}/resync`,
      {},
    );
  }

  /**
   * MVP-3b — bucketed history for the sparkline drawer. Default window
   * is 24 hours.
   */
  getHistory(integrationId: string, windowHours = 24): Observable<IntegrationHistoryBucket[]> {
    const params = new HttpParams().set('windowHours', String(windowHours));
    return this.http.get<IntegrationHistoryBucket[]>(
      `/super-admin/integrations/${encodeURIComponent(integrationId)}/history`,
      { params },
    );
  }
}
