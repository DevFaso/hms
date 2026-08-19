import { HttpClient, HttpHeaders } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
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

/**
 * MVP-4b — surface a countdown to the active impersonation TTL so the
 * banner can warn the operator before the token silently expires.
 *
 * Tick frequency is 1 s — cheap, drives a smooth visible countdown, and
 * means the auto-stop fires within a second of the actual expiry rather
 * than waiting for the next user request to surface a 401.
 */
const COUNTDOWN_TICK_MS = 1_000;
/** Threshold (ms) below which the banner switches to "warning" styling. */
const COUNTDOWN_WARN_MS = 2 * 60 * 1000;

@Injectable({ providedIn: 'root' })
export class ImpersonationService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly destroyRef = inject(DestroyRef);

  readonly active = signal<ImpersonationActiveResponse | null>(null);

  /**
   * Wall-clock expiry of the active impersonation token, mirrored from
   * the start response (or persisted across page reloads via
   * sessionStorage). Null when no impersonation is active.
   */
  readonly expiresAt = signal<Date | null>(null);

  /**
   * Tick signal (ms remaining until expiry). Updated by the 1 s
   * countdown interval; computed views read this for live UI.
   */
  readonly remainingMs = signal<number | null>(null);

  /** Convenience computed view: ≤ 2 min and impersonation is active. */
  readonly nearingExpiry = computed(() => {
    const remaining = this.remainingMs();
    return this.isActive() && remaining !== null && remaining <= COUNTDOWN_WARN_MS && remaining > 0;
  });

  private countdownHandle: ReturnType<typeof setInterval> | null = null;

  constructor() {
    // Clean up the interval on destroy (e.g. test teardown). The service
    // is `providedIn: 'root'` so DestroyRef fires on root-injector shutdown.
    this.destroyRef.onDestroy(() => this.stopCountdown());
  }

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
            expiresAt: response.expiresAt,
          });

          // MVP-4b: arm the countdown from the server-issued expiry so
          // the banner can show "expires in 4:59" and auto-stop when the
          // token actually expires (rather than waiting for the next
          // request to surface a 401).
          this.startCountdown(response.expiresAt);
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
    return this.http.get<ImpersonationActiveResponse>('/super-admin/impersonation/active').pipe(
      tap((response) => {
        this.active.set(response);
        // MVP-4b: re-arm countdown after a page refresh so the banner
        // shows the right remaining time even if the user reloaded
        // mid-impersonation. Tolerant when expiresAt is missing
        // (legacy backend) — countdown stays at null and the banner
        // hides the badge.
        if (response.impersonating && response.expiresAt) {
          this.startCountdown(response.expiresAt);
        } else if (!response.impersonating) {
          this.stopCountdown();
        }
      }),
    );
  }

  // ── MVP-4b: countdown helpers ─────────────────────────────────────

  private startCountdown(expiresAtIso: string | null | undefined): void {
    this.stopCountdown();
    if (!expiresAtIso) {
      this.expiresAt.set(null);
      this.remainingMs.set(null);
      return;
    }
    const expiry = new Date(expiresAtIso);
    if (Number.isNaN(expiry.getTime())) {
      // Bad timestamp — fail safe and don't auto-stop. The user can
      // exit manually; the next 401 will catch any stuck session.
      this.expiresAt.set(null);
      this.remainingMs.set(null);
      return;
    }
    this.expiresAt.set(expiry);
    this.tickCountdown();
    this.countdownHandle = setInterval(() => this.tickCountdown(), COUNTDOWN_TICK_MS);
  }

  private tickCountdown(): void {
    const expiry = this.expiresAt();
    if (!expiry) {
      this.remainingMs.set(null);
      return;
    }
    const remaining = expiry.getTime() - Date.now();
    this.remainingMs.set(Math.max(0, remaining));
    if (remaining <= 0) {
      // TTL elapsed — drop the token client-side without waiting for
      // the next server hit. Restores the pre-impersonation session
      // so the operator lands back on their super-admin context.
      this.stopCountdown();
      this.forceStop();
    }
  }

  private stopCountdown(): void {
    if (this.countdownHandle !== null) {
      clearInterval(this.countdownHandle);
      this.countdownHandle = null;
    }
    // Reset the published signals so a refreshActive() that finds the
    // session ended server-side leaves no stale countdown showing in
    // the banner. restoreOriginalSession() also resets them up-front
    // so the second clear here is a no-op in the boundary path.
    this.expiresAt.set(null);
    this.remainingMs.set(null);
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
    // MVP-4b: tear down the countdown first so a stale tick can't
    // re-trigger forceStop() while we're already restoring.
    this.stopCountdown();
    this.expiresAt.set(null);
    this.remainingMs.set(null);

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
