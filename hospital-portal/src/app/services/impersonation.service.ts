import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import {
  ImpersonationActiveResponse,
  ImpersonationStartRequest,
  ImpersonationStartResponse,
} from './impersonation.model';

/**
 * MVP-4 — Support impersonation client.
 *
 * Stash the original super-admin token under {@link ORIGINAL_TOKEN_KEY}
 * before swapping in the impersonation token; restore on stop. The
 * {@link active} signal mirrors the banner state so any component can
 * conditionally render off it without subscribing to an HTTP call.
 */
const ORIGINAL_TOKEN_KEY = 'auth_token_pre_impersonation';

@Injectable({ providedIn: 'root' })
export class ImpersonationService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  readonly active = signal<ImpersonationActiveResponse | null>(null);

  start(
    request: ImpersonationStartRequest,
    mfaToken?: string,
  ): Observable<ImpersonationStartResponse> {
    const headers = new HttpHeaders(mfaToken ? { 'X-Mfa-Token': mfaToken } : {});
    return this.http
      .post<ImpersonationStartResponse>('/super-admin/impersonation/start', request, { headers })
      .pipe(
        tap((response) => {
          const original = this.auth.getToken();
          if (original) {
            sessionStorage.setItem(ORIGINAL_TOKEN_KEY, original);
          }
          this.auth.setToken(response.accessToken, /* remember */ false);
          this.active.set({
            impersonating: true,
            impersonatorUserId: response.impersonatorUserId,
            impersonatorUsername: response.impersonatorUsername,
            targetUserId: response.targetUserId,
            targetUsername: response.targetUsername,
          });
        }),
      );
  }

  stop(): Observable<ImpersonationActiveResponse> {
    return this.http
      .post<ImpersonationActiveResponse>('/super-admin/impersonation/stop', {})
      .pipe(tap((response) => this.restoreOriginalToken(response)));
  }

  /** Discards the impersonation token without calling the server. Used on 401 / expiry. */
  forceStop(): void {
    this.restoreOriginalToken({ impersonating: false });
  }

  refreshActive(): Observable<ImpersonationActiveResponse> {
    return this.http
      .get<ImpersonationActiveResponse>('/super-admin/impersonation/active')
      .pipe(tap((response) => this.active.set(response)));
  }

  private restoreOriginalToken(response: ImpersonationActiveResponse): void {
    const original = sessionStorage.getItem(ORIGINAL_TOKEN_KEY);
    if (original) {
      this.auth.setToken(original, /* remember */ true);
      sessionStorage.removeItem(ORIGINAL_TOKEN_KEY);
    } else {
      this.auth.clearToken();
    }
    this.active.set({ ...response, impersonating: false });
  }
}
