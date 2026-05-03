import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { AuthService, JwtPayload, LoginUserProfile } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import {
  ImpersonationActiveResponse,
  ImpersonationStartRequest,
  ImpersonationStartResponse,
} from './impersonation.model';

/**
 * MVP-4 — Support impersonation client.
 *
 * Stash the original super-admin token under {@link ORIGINAL_TOKEN_KEY},
 * the original `remember` preference under {@link ORIGINAL_REMEMBER_KEY},
 * and the original profile snapshot under {@link ORIGINAL_PROFILE_KEY}
 * before swapping in the impersonation token; restore all three on stop.
 *
 * <p>Beyond the obvious token swap, the service also re-hydrates
 * {@link RoleContextService} and the persisted user profile from the new
 * JWT's claims so the shell, role guards, and side-nav reflect the
 * impersonated identity immediately — closing Copilot review #1 + #3 on
 * PR #224 (without this, the super-admin nav stays visible during
 * impersonation and `RoleGuard` bounces the operator to /error/403 on
 * exit until they reload the page).
 *
 * <p>The {@link active} signal mirrors the banner state so any component
 * can conditionally render off it without subscribing to an HTTP call.
 */
const ORIGINAL_TOKEN_KEY = 'auth_token_pre_impersonation';
const ORIGINAL_REMEMBER_KEY = 'auth_remember_pre_impersonation';
const ORIGINAL_PROFILE_KEY = 'auth_profile_pre_impersonation';

@Injectable({ providedIn: 'root' })
export class ImpersonationService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);

  readonly active = signal<ImpersonationActiveResponse | null>(null);

  /** True while an impersonation token is active. The 401 interceptor checks
   *  this before letting itself trigger a refresh — see Copilot review #4. */
  isActive(): boolean {
    return this.active()?.impersonating === true;
  }

  start(
    request: ImpersonationStartRequest,
    mfaToken?: string,
  ): Observable<ImpersonationStartResponse> {
    const headers = new HttpHeaders(mfaToken ? { 'X-Mfa-Token': mfaToken } : {});
    return this.http
      .post<ImpersonationStartResponse>('/super-admin/impersonation/start', request, { headers })
      .pipe(
        tap((response) => {
          // 1. Save original session state so stop() can restore it
          //    bit-for-bit (closes Copilot review #5: don't silently
          //    promote a session-only login to remember-me).
          this.preserveOriginalSession();

          // 2. Swap in the impersonation token. setToken() now clears the
          //    OTHER storage so a remember-me login does not leave the
          //    original token in localStorage shadowing the new one.
          //    Impersonation tokens always go into sessionStorage so they
          //    are dropped when the tab closes (defence in depth — see
          //    Copilot review #2).
          this.auth.setToken(response.accessToken, /* remember */ false);

          // 3. Re-hydrate RoleContext + profile from the new claims so the
          //    shell, role guards, and side-nav reflect the impersonated
          //    identity immediately (closes Copilot review #1 + #3).
          this.hydrateFromImpersonationToken(response.accessToken, response);

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
      .pipe(tap((response) => this.restoreOriginalSession(response)));
  }

  /** Discards the impersonation token without calling the server. Used on 401 / expiry. */
  forceStop(): void {
    this.restoreOriginalSession({ impersonating: false });
  }

  refreshActive(): Observable<ImpersonationActiveResponse> {
    return this.http
      .get<ImpersonationActiveResponse>('/super-admin/impersonation/active')
      .pipe(tap((response) => this.active.set(response)));
  }

  // ─── private helpers ──────────────────────────────────────────────

  private preserveOriginalSession(): void {
    const original = this.auth.getToken();
    if (original) {
      sessionStorage.setItem(ORIGINAL_TOKEN_KEY, original);
    }
    sessionStorage.setItem(ORIGINAL_REMEMBER_KEY, this.auth.isTokenRemembered() ? '1' : '0');
    const profile = this.auth.getUserProfile();
    if (profile) {
      sessionStorage.setItem(ORIGINAL_PROFILE_KEY, JSON.stringify(profile));
    }
  }

  private restoreOriginalSession(response: ImpersonationActiveResponse): void {
    const original = sessionStorage.getItem(ORIGINAL_TOKEN_KEY);
    const rememberFlag = sessionStorage.getItem(ORIGINAL_REMEMBER_KEY);
    const remember = rememberFlag !== '0'; // default true when flag missing/legacy

    if (original) {
      this.auth.setToken(original, remember);
      sessionStorage.removeItem(ORIGINAL_TOKEN_KEY);
    } else {
      this.auth.clearToken();
    }
    sessionStorage.removeItem(ORIGINAL_REMEMBER_KEY);

    // Restore profile + role context from the snapshot taken at start().
    // If snapshot is missing (legacy session), fall back to decoding the
    // restored token so role state isn't left mid-impersonation stale.
    const snapshot = sessionStorage.getItem(ORIGINAL_PROFILE_KEY);
    if (snapshot) {
      try {
        const profile = JSON.parse(snapshot) as LoginUserProfile;
        this.auth.setUserProfile(profile);
        this.roleContext.setRoles(profile.roles ?? []);
        const ids = profile.hospitalIds ?? [];
        this.roleContext.setPermittedHospitalIds(ids);
        if (profile.primaryHospitalId) {
          this.roleContext.activeHospitalId = profile.primaryHospitalId;
        }
      } catch {
        // Snapshot corrupt — fall back to token decode
        this.hydrateFromCurrentToken();
      }
      sessionStorage.removeItem(ORIGINAL_PROFILE_KEY);
    } else if (original) {
      this.hydrateFromCurrentToken();
    }

    this.active.set({ ...response, impersonating: false });
  }

  private hydrateFromImpersonationToken(
    accessToken: string,
    response: ImpersonationStartResponse,
  ): void {
    const claims = this.decodeJwt(accessToken);
    const roles = this.normalizeRoles(claims?.roles);
    this.roleContext.setRoles(roles);

    // Build a minimal profile reflecting the impersonated target so the
    // shell renders the target's name / role chip rather than the super
    // admin's identity. Persisted under the same USER_PROFILE_KEY as a
    // normal login so getUserProfile() returns it.
    const profile: LoginUserProfile = {
      id: response.targetUserId,
      username: response.targetUsername,
      email: '',
      roles,
      active: true,
      primaryHospitalId: this.firstString(claims?.['primaryHospitalId']),
      hospitalIds: this.stringArray(claims?.['hospitalIds']),
    };
    this.auth.setUserProfile(profile);

    const permittedIds = profile.hospitalIds ?? [];
    this.roleContext.setPermittedHospitalIds(permittedIds);
    if (profile.primaryHospitalId) {
      this.roleContext.activeHospitalId = profile.primaryHospitalId;
    } else if (permittedIds.length === 1) {
      this.roleContext.activeHospitalId = permittedIds[0];
    } else {
      this.roleContext.activeHospitalId = null;
    }
  }

  private hydrateFromCurrentToken(): void {
    const token = this.auth.getToken();
    if (!token) return;
    const claims = this.decodeJwt(token);
    if (!claims) return;
    const roles = this.normalizeRoles(claims.roles);
    this.roleContext.setRoles(roles);
    const permittedIds = this.stringArray(claims['hospitalIds']) ?? [];
    this.roleContext.setPermittedHospitalIds(permittedIds);
    const primary = this.firstString(claims['primaryHospitalId']);
    if (primary) {
      this.roleContext.activeHospitalId = primary;
    }
  }

  private decodeJwt(token: string): JwtPayload | null {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    try {
      const padded = parts[1].replaceAll('-', '+').replaceAll('_', '/');
      const pad = padded.length % 4;
      const final_ = pad ? padded + '===='.slice(pad) : padded;
      return JSON.parse(atob(final_)) as JwtPayload;
    } catch {
      return null;
    }
  }

  private normalizeRoles(value: unknown): string[] {
    if (!Array.isArray(value)) return [];
    return value.filter((r): r is string => typeof r === 'string');
  }

  private stringArray(value: unknown): string[] | undefined {
    if (!Array.isArray(value)) return undefined;
    return value.filter((v): v is string => typeof v === 'string');
  }

  private firstString(value: unknown): string | undefined {
    return typeof value === 'string' && value.length > 0 ? value : undefined;
  }
}
