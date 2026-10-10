import { HttpClient, HttpContext, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  HospitalLifecyclePurgeSchedule,
  HospitalLifecycleReason,
  HospitalLifecycleResponse,
} from './hospital-lifecycle.model';
import { MFA_TOKEN_HEADER, STEP_UP_REQUEST } from './step-up';

const BASE = '/super-admin/hospitals';

/**
 * MVP-c batch — calls the six SuperAdminHospitalLifecycleController
 * endpoints. All endpoints are gated `ROLE_SUPER_ADMIN` server-side.
 */
/**
 * The options of a step-up call (suspend, archive, schedule-purge): the
 * current authenticator or backup code as `X-Mfa-Token` when given, and the
 * {@link STEP_UP_REQUEST} mark so a refused code never signs the user out.
 */
function stepUp(mfaCode?: string | null): { headers?: HttpHeaders; context: HttpContext } {
  const code = mfaCode?.trim();
  return {
    ...(code ? { headers: new HttpHeaders({ [MFA_TOKEN_HEADER]: code }) } : {}),
    context: new HttpContext().set(STEP_UP_REQUEST, true),
  };
}

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
    mfaCode?: string | null,
  ): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/suspend`,
      body,
      stepUp(mfaCode),
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
    mfaCode?: string | null,
  ): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/archive`,
      body,
      stepUp(mfaCode),
    );
  }

  schedulePurge(
    hospitalId: string,
    body: HospitalLifecyclePurgeSchedule,
    mfaCode?: string | null,
  ): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/schedule-purge`,
      body,
      stepUp(mfaCode),
    );
  }

  cancelPurge(hospitalId: string): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/cancel-purge`,
      {},
    );
  }
}
