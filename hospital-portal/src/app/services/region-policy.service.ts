import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { OrganizationRegion } from './data-residency.model';
import {
  RegionPolicyCapabilities,
  RegionPolicyRow,
  RegionPolicyUpdate,
} from './region-policy.model';

// Mirrors SuperAdminRegionPolicyController @RequestMapping; the prior
// base path /super-admin/region-policies was a pre-existing typo that
// would 404 in production (Karma specs use SpyObj so the mismatch
// never surfaced in CI).
const BASE = '/super-admin/data-residency/policies';

/**
 * MVP-9c — per-region policy CRUD for the data-residency policy
 * editor. All endpoints are gated `ROLE_SUPER_ADMIN` server-side.
 */
@Injectable({ providedIn: 'root' })
export class RegionPolicyService {
  private readonly http = inject(HttpClient);

  list(): Observable<RegionPolicyRow[]> {
    return this.http.get<RegionPolicyRow[]>(BASE);
  }

  get(region: OrganizationRegion): Observable<RegionPolicyRow> {
    return this.http.get<RegionPolicyRow>(`${BASE}/${region}`);
  }

  update(region: OrganizationRegion, body: RegionPolicyUpdate): Observable<RegionPolicyRow> {
    return this.http.put<RegionPolicyRow>(`${BASE}/${region}`, body);
  }

  capabilities(): Observable<RegionPolicyCapabilities> {
    return this.http.get<RegionPolicyCapabilities>(`${BASE}/capabilities`);
  }
}
