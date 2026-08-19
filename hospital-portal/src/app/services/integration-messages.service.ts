import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  IntegrationMessageEvent,
  IntegrationMessagePage,
  IntegrationMessageSearchFilter,
} from './integration-messages.model';

const BASE = '/super-admin/integration-messages';

/**
 * MVP-c3 — Bridges-style message-trace + replay surface. Mirrors
 * `SuperAdminIntegrationMessageController`. All endpoints are gated
 * `ROLE_SUPER_ADMIN` on the server.
 */
@Injectable({ providedIn: 'root' })
export class IntegrationMessagesService {
  private readonly http = inject(HttpClient);

  search(filter: IntegrationMessageSearchFilter = {}): Observable<IntegrationMessagePage> {
    let params = new HttpParams();
    if (filter.integrationId) {
      params = params.set('integrationId', filter.integrationId);
    }
    if (filter.organizationId) {
      params = params.set('organizationId', filter.organizationId);
    }
    if (filter.status) {
      params = params.set('status', filter.status);
    }
    if (filter.fromDate) {
      params = params.set('fromDate', filter.fromDate);
    }
    if (filter.toDate) {
      params = params.set('toDate', filter.toDate);
    }
    if (filter.page !== undefined) {
      params = params.set('page', String(filter.page));
    }
    if (filter.size !== undefined) {
      params = params.set('size', String(filter.size));
    }
    return this.http.get<IntegrationMessagePage>(BASE, { params });
  }

  /**
   * Fetch a single message including the full payload. Pairs with
   * the search() response which elides the payload to keep list
   * pages small.
   */
  getById(messageId: string): Observable<IntegrationMessageEvent> {
    return this.http.get<IntegrationMessageEvent>(`${BASE}/${messageId}`);
  }

  replay(messageId: string): Observable<IntegrationMessageEvent> {
    return this.http.post<IntegrationMessageEvent>(`${BASE}/${messageId}/replay`, {});
  }
}
