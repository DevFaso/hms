import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  OrganizationRegion,
  OrganizationRegionRow,
  OrganizationRegionUpdate,
} from './data-residency.model';

const BASE = '/api/super-admin/organizations';

/**
 * MVP-9: Data-residency / region-tagging endpoints. All require
 * ROLE_SUPER_ADMIN.
 */
@Injectable({ providedIn: 'root' })
export class DataResidencyService {
  private readonly http = inject(HttpClient);

  listAvailableRegions(): Observable<OrganizationRegion[]> {
    return this.http.get<OrganizationRegion[]>(`${BASE}/regions`);
  }

  getRegionSnapshot(): Observable<OrganizationRegionRow[]> {
    return this.http.get<OrganizationRegionRow[]>(`${BASE}/region-snapshot`);
  }

  getRegion(organizationId: string): Observable<OrganizationRegionRow> {
    return this.http.get<OrganizationRegionRow>(`${BASE}/${organizationId}/region`);
  }

  updateRegion(
    organizationId: string,
    body: OrganizationRegionUpdate,
  ): Observable<OrganizationRegionRow> {
    return this.http.post<OrganizationRegionRow>(`${BASE}/${organizationId}/region`, body);
  }
}
