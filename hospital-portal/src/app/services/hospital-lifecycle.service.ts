import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  HospitalLifecyclePurgeSchedule,
  HospitalLifecycleReason,
  HospitalLifecycleResponse,
} from './hospital-lifecycle.model';

const BASE = '/super-admin/hospitals';

/**
 * MVP-c batch — calls the six SuperAdminHospitalLifecycleController
 * endpoints. All endpoints are gated `ROLE_SUPER_ADMIN` server-side.
 */
@Injectable({ providedIn: 'root' })
export class HospitalLifecycleService {
  private readonly http = inject(HttpClient);

  get(hospitalId: string): Observable<HospitalLifecycleResponse> {
    return this.http.get<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/lifecycle`,
    );
  }

  suspend(
    hospitalId: string,
    body: HospitalLifecycleReason,
  ): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/suspend`,
      body,
    );
  }

  restore(hospitalId: string): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/restore`,
      {},
    );
  }

  archive(
    hospitalId: string,
    body: HospitalLifecycleReason,
  ): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/archive`,
      body,
    );
  }

  schedulePurge(
    hospitalId: string,
    body: HospitalLifecyclePurgeSchedule,
  ): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/schedule-purge`,
      body,
    );
  }

  cancelPurge(hospitalId: string): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/cancel-purge`,
      {},
    );
  }
}
