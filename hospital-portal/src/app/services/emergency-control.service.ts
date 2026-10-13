import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  EmergencyActionResponse,
  EmergencyBroadcastRequest,
  EmergencyForceLogoutRequest,
  EmergencyForceMfaRequest,
  EmergencyKillFeatureRequest,
} from './emergency-control.model';
import { stepUpRequestOptions } from './step-up';

const BASE = '/api/super-admin/emergency';

/**
 * MVP-7: Calls super-admin emergency endpoints. Every request carries
 * the caller-supplied X-Mfa-Token (same step-up plumbing as MVP-2 +
 * MVP-4) so the backend can reject without a fresh MFA challenge.
 */
@Injectable({ providedIn: 'root' })
export class EmergencyControlService {
  private readonly http = inject(HttpClient);

  forceLogoutAll(
    body: EmergencyForceLogoutRequest,
    mfaToken: string,
  ): Observable<EmergencyActionResponse> {
    return this.http.post<EmergencyActionResponse>(
      `${BASE}/force-logout-all`,
      body,
      stepUpRequestOptions(mfaToken),
    );
  }

  killFeature(
    body: EmergencyKillFeatureRequest,
    mfaToken: string,
  ): Observable<EmergencyActionResponse> {
    return this.http.post<EmergencyActionResponse>(
      `${BASE}/kill-feature`,
      body,
      stepUpRequestOptions(mfaToken),
    );
  }

  forceMfaReenrol(
    body: EmergencyForceMfaRequest,
    mfaToken: string,
  ): Observable<EmergencyActionResponse> {
    return this.http.post<EmergencyActionResponse>(
      `${BASE}/force-mfa-reenrol`,
      body,
      stepUpRequestOptions(mfaToken),
    );
  }

  broadcast(
    body: EmergencyBroadcastRequest,
    mfaToken: string,
  ): Observable<EmergencyActionResponse> {
    return this.http.post<EmergencyActionResponse>(
      `${BASE}/broadcast`,
      body,
      stepUpRequestOptions(mfaToken),
    );
  }
}
