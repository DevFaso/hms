import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  IntegrationHealthRow,
  IntegrationHealthSummary,
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
}
