import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  EmergencyActionResponse,
  EmergencyBroadcastRequest,
  EmergencyForceLogoutRequest,
  EmergencyForceMfaRequest,
  EmergencyKillFeatureRequest,
} from './emergency-control.model';

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
    return this.http.post<EmergencyActionResponse>(`${BASE}/force-logout-all`, body, {
      headers: this.mfaHeaders(mfaToken),
    });
  }

  killFeature(
    body: EmergencyKillFeatureRequest,
    mfaToken: string,
  ): Observable<EmergencyActionResponse> {
    return this.http.post<EmergencyActionResponse>(`${BASE}/kill-feature`, body, {
      headers: this.mfaHeaders(mfaToken),
    });
  }

  forceMfaReenrol(
    body: EmergencyForceMfaRequest,
    mfaToken: string,
  ): Observable<EmergencyActionResponse> {
    return this.http.post<EmergencyActionResponse>(`${BASE}/force-mfa-reenrol`, body, {
      headers: this.mfaHeaders(mfaToken),
    });
  }

  broadcast(
    body: EmergencyBroadcastRequest,
    mfaToken: string,
  ): Observable<EmergencyActionResponse> {
    return this.http.post<EmergencyActionResponse>(`${BASE}/broadcast`, body, {
      headers: this.mfaHeaders(mfaToken),
    });
  }

  private mfaHeaders(mfaToken: string): HttpHeaders {
    return new HttpHeaders({ 'X-Mfa-Token': mfaToken });
  }
}
