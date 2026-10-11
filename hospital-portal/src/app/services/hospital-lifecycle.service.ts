import { HttpClient, HttpContext, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  HospitalLifecyclePurgeSchedule,
  HospitalLifecycleReason,
  HospitalLifecycleResponse,
} from './hospital-lifecycle.model';
import { MFA_TOKEN_HEADER, STEP_UP_REQUEST, isStepUpAction } from './step-up';

const BASE = '/super-admin/hospitals';

/**
 * MVP-c batch — calls the six SuperAdminHospitalLifecycleController
 * endpoints. All endpoints are gated `ROLE_SUPER_ADMIN` server-side.
 */
/**
 * The options of a lifecycle call: for a step-up action (the one
 * {@link isStepUpAction} list), the current authenticator or backup code as
 * `X-Mfa-Token` when given, and the {@link STEP_UP_REQUEST} mark so a refused
 * code never signs the user out.
 */
function stepUp(
  action: string,
  mfaCode?: string | null,
): { headers?: HttpHeaders; context: HttpContext } {
  const marked = isStepUpAction(action);
  const code = marked ? mfaCode?.trim() : undefined;
  return {
    ...(code ? { headers: new HttpHeaders({ [MFA_TOKEN_HEADER]: code }) } : {}),
    context: new HttpContext().set(STEP_UP_REQUEST, marked),
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
      stepUp('suspend', mfaCode),
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
      stepUp('archive', mfaCode),
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
      stepUp('schedule-purge', mfaCode),
    );
  }

  cancelPurge(hospitalId: string): Observable<HospitalLifecycleResponse> {
    return this.http.post<HospitalLifecycleResponse>(
      `${BASE}/${encodeURIComponent(hospitalId)}/cancel-purge`,
      {},
    );
  }
}
