import { isPlatformBrowser } from '@angular/common';
import { HttpClient, HttpContext, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, PLATFORM_ID, signal, DestroyRef } from '@angular/core';
import { EMPTY, Observable, Subject, catchError } from 'rxjs';

import { RoleContextService } from '../core/role-context.service';
import { expandRoleEquivalents } from '../core/role-equivalence';
import { ApiTimeoutService } from '../core/api-timeout.service';
import { SESSION_RESTORE_HEADER, SESSION_RESTORE_REQUEST } from './session-lifetime';
import {
  bindTab,
  clearSessionScopedStorage,
  clearSsoTab,
  clearTabSession,
  clearUserDrafts,
  purgeLegacyBrowserStorage,
  tabBinding,
  tabBindingFor,
} from '../core/browser-storage';

const REFRESH_TOKEN_KEY = 'auth_refresh_token';

/**
 * Authoritative session context returned by GET /api/auth/session/bootstrap.
 * Replaces client-side JWT claim decoding for hospital/permission resolution.
 */
export interface SessionBootstrapResponse {
  userId: string;
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  profileImageUrl?: string;
  /** 'internal' | 'keycloak' | 'saml' */
  authSource: string;
  roles: string[];
  superAdmin: boolean;
  hospitalAdmin: boolean;
  /** Confined to a provider facility (pharmacy or laboratory). */
  providerUser?: boolean;
  primaryHospitalId?: string;
  primaryHospitalName?: string;
  permittedHospitalIds?: string[];
  /** Null when the user has no staff record */
  staffId?: string;
  staffRoleCode?: string;
  departmentId?: string;
  departmentName?: string;
  /** Null when the user has no patient record */
  patientId?: string;
  lastOidcLoginAt?: string;
}

export interface LoginUserProfile {
  id: string;
  username: string;
  email: string;
  firstName?: string;
  lastName?: string;
  phoneNumber?: string;
  profileImageUrl?: string;
  roles: string[];
  profileType?: 'STAFF' | 'PATIENT';
  licenseNumber?: string;
  staffId?: string;
  roleName?: string;
  active: boolean;
  forcePasswordChange?: boolean;
  forceUsernameChange?: boolean;
  /** Primary hospital this user is assigned to (from active assignment). */
  primaryHospitalId?: string;
  /** Display name of the primary hospital. */
  primaryHospitalName?: string;
  /** All hospital IDs this user is permitted to access, as the session last reported them. */
  hospitalIds?: string[];
}

export interface JwtPayload {
  sub?: string;
  exp?: number;
  roles?: string[];
  authorities?: string[];
  scope?: string;
  uid?: string;
  id?: string;
  hospitalId?: string;
  [claim: string]: unknown;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private static readonly ROLE_HIERARCHY = [
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ADMIN',
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_RECEPTIONIST',
    'ROLE_LAB_SCIENTIST',
    'ROLE_STAFF',
    'ROLE_PATIENT',
  ];

  private static readonly ROLE_PRIORITY = new Map(
    AuthService.ROLE_HIERARCHY.map((role, index) => [role, index]),
  );

  private readonly roleContext = inject(RoleContextService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly http = inject(HttpClient);
  private readonly timeouts = inject(ApiTimeoutService);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** Reactive signal that updates whenever setUserProfile() is called. */
  readonly currentProfile = signal<LoginUserProfile | null>(null);

  private readonly sessionEnd = new Subject<void>();
  readonly sessionEnded$ = this.sessionEnd.asObservable();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.endSession());
    // Earlier builds kept the session in web storage: remove what they left.
    if (this.isBrowser) purgeLegacyBrowserStorage();
  }

  private endSession(): void {
    this.timeouts.resetForSession();
    this.sessionEnd.next();
  }

  // ---------- Storage ----------
  // The access token lives in memory only, never in web storage. A reload of
  // a tab that signed in restores it through the refresh cookie
  // (session-restore.ts), or through the identity provider after an SSO
  // sign-in (oidc-auth.service.ts); a tab opened fresh signs in again (a
  // duplicated or browser-restored tab carries the tab's binding with it).
  // See core/browser-storage.ts.
  private accessToken: string | null = null;

  getToken(): string | null {
    if (!this.isBrowser) return null;
    return this.accessToken;
  }

  setToken(token: string): void {
    if (!this.isBrowser) return;
    this.accessToken = token;
    // Bind the tab to this account for a reload (an opaque digest, no credential).
    // The idle lock is left alone: a refresh while the screen is locked, or the
    // restore after a reload, must not unlock it (see clearIdleLock for sign-in).
    // While impersonating, the tab stays bound to the super admin: a reload
    // restores the admin's own session (the server ends the impersonation).
    if (this.originalSession.token) return;
    // A native token: the tab now restores through the refresh cookie (an SSO
    // sign-in marks the tab again right after, see OidcAuthService).
    clearSsoTab();
    const subject = this.tokenSubject(token);
    if (!subject) return;
    void tabBindingFor(subject)
      .then((binding) => {
        if (this.accessToken === token) bindTab(binding);
      })
      .catch(() => undefined);
  }

  clearToken(): void {
    if (!this.isBrowser) return;
    this.accessToken = null;
    clearTabSession();
  }

  /** This tab signed in and has not signed out: a reload asks the refresh cookie. */
  hasTabSession(): boolean {
    return this.isBrowser && tabBinding() !== null;
  }

  /**
   * Whether a token minted after a reload is for the account this tab signed in
   * as (the binding set by setToken). False when the tab has no binding.
   */
  async isTokenForThisTab(token: string): Promise<boolean> {
    const bound = tabBinding();
    const subject = this.tokenSubject(token);
    if (!bound || !subject) return false;
    return (await tabBindingFor(subject)) === bound;
  }

  /** The account a token names: `uid`, else `id`, else `sub`. */
  private tokenSubject(token: string): string | null {
    const p = this.decodePayload(token);
    const subject = (p?.uid as string) ?? (p?.id as string) ?? p?.sub ?? null;
    return typeof subject === 'string' && subject ? subject : null;
  }

  /**
   * Whether a token the refresh cookie minted is for the account this tab
   * shows. Every tab of the browser shares that cookie, so after someone else
   * signs in from another tab it mints their token: the tab must then end its
   * own session ({@link endLocalSession}) rather than act under another name.
   */
  isTokenForShownAccount(token: string): boolean {
    const shown = this.getUserProfile()?.id ?? this.getUserId();
    const minted = this.decodePayload(token);
    const mintedId = (minted?.uid as string) ?? (minted?.id as string) ?? null;
    return !shown || !mintedId || shown === mintedId;
  }

  // ---------- Refresh Token ----------
  // S-01: refresh token now lives in an HttpOnly cookie (`hms_refresh`)
  // set by the backend. JavaScript cannot read it, which mitigates XSS
  // theft. The accessor methods below remain for backward compatibility
  // (they still purge any legacy localStorage value left over from a
  // previous session) but no new refresh tokens are written to storage.

  /**
   * @deprecated S-01: refresh tokens are no longer accessible from JS.
   * Returns any value left over from a pre-S-01 session so the next
   * refresh call can use it once and migrate to the cookie.
   */
  getRefreshToken(): string | null {
    if (!this.isBrowser) return null;
    try {
      return (
        localStorage.getItem(REFRESH_TOKEN_KEY) || sessionStorage.getItem(REFRESH_TOKEN_KEY) || null
      );
    } catch {
      return null;
    }
  }

  /**
   * @deprecated S-01: no-op. The refresh token is delivered exclusively
   * via an HttpOnly cookie. Any legacy storage is wiped so future calls
   * always rely on the cookie.
   */
  setRefreshToken(_token: string, _remember = true): void {
    // Intentionally do not persist the refresh token to web storage.
    // Wipe any legacy value so subsequent reads see only the cookie.
    this.clearRefreshToken();
  }

  clearRefreshToken(): void {
    if (!this.isBrowser) return;
    try {
      localStorage.removeItem(REFRESH_TOKEN_KEY);
      sessionStorage.removeItem(REFRESH_TOKEN_KEY);
    } catch {
      // Storage not available
    }
  }

  /**
   * Call POST /api/auth/token/refresh.
   *
   * <p>S-01: the refresh token is sent automatically by the browser as the
   * HttpOnly `hms_refresh` cookie (scoped to /api/auth). We pass
   * `withCredentials: true` so cross-origin cookies are included.
   *
   * <p>If a legacy localStorage refresh token still exists from a pre-S-01
   * session, it is forwarded once in the body so the backend can rotate it
   * and switch the user over to the cookie-based flow.
   */
  refreshTokenRequest(restore = false): Observable<{ accessToken: string; refreshToken: string }> {
    const legacyRefreshToken = this.getRefreshToken();
    const baseUrl = this.isBrowser ? `${globalThis.location.origin}/api` : '/api';
    const body = legacyRefreshToken ? { refreshToken: legacyRefreshToken } : {};
    return this.http.post<{ accessToken: string; refreshToken: string }>(
      `${baseUrl}/auth/token/refresh`,
      body,
      restore
        ? {
            withCredentials: true,
            headers: { [SESSION_RESTORE_HEADER]: '1' },
            context: new HttpContext().set(SESSION_RESTORE_REQUEST, true),
          }
        : { withCredentials: true },
    );
  }

  // ---------- User Profile ----------
  // Same home as the token: memory only. After a reload the session
  // bootstrap rebuilds it (SessionScopeService.hydrate).
  private profileCache: LoginUserProfile | null = null;

  setUserProfile(profile: LoginUserProfile): void {
    if (!this.isBrowser) return;
    if (this.getUserProfile()?.id !== profile.id) {
      this.timeouts.resetForSession();
      // Another account takes over the tab: nothing the previous one drafted stays.
      if (this.profileCache) clearUserDrafts();
    }
    const toStore = { ...profile };
    if (toStore.profileImageUrl) {
      toStore.profileImageUrl = this.normalizeImageUrl(toStore.profileImageUrl);
    }
    this.profileCache = toStore;
    this.currentProfile.set(toStore);
  }

  /**
   * Normalises a profile image URL so it always resolves through the public
   * Nginx proxy, regardless of whether the backend returned:
   *   - a relative path  ("/api/uploads/...")          → kept as-is
   *   - the public origin ("https://hms.dev.../api/…") → kept as-is
   *   - an internal Railway URL ("http://patient-stillness.railway.internal:8080/api/…")
   *     → rewritten to a relative "/api/uploads/…" path
   */
  private normalizeImageUrl(url: string): string {
    // Already relative — good.
    if (!url.startsWith('http')) return url;

    const publicOrigin = this.isBrowser ? globalThis.location.origin : '';

    // Already pointing at the public origin — good.
    if (publicOrigin && url.startsWith(publicOrigin)) return url;

    // Internal / mismatched origin: extract the path component and keep it
    // relative so the request goes through Nginx → backend.
    try {
      const parsed = new URL(url);
      return parsed.pathname + (parsed.search || '');
    } catch {
      return url;
    }
  }

  getUserProfile(): LoginUserProfile | null {
    if (!this.isBrowser) return null;
    return this.profileCache;
  }

  clearUserProfile(): void {
    if (!this.isBrowser) return;
    this.profileCache = null;
    this.currentProfile.set(null);
  }

  // ---------- Impersonation snapshot ----------
  // The super-admin's own token and profile while impersonating: memory only,
  // dropped with the rest of the session at sign-out.
  private originalSession: { token: string | null; profile: LoginUserProfile | null } = {
    token: null,
    profile: null,
  };

  holdOriginalSession(token: string | null, profile: LoginUserProfile | null): void {
    this.originalSession = { token, profile };
  }

  /** Hands the snapshot back once and forgets it. */
  releaseOriginalSession(): { token: string | null; profile: LoginUserProfile | null } {
    const held = this.originalSession;
    this.originalSession = { token: null, profile: null };
    return held;
  }

  // ---------- JWT Helpers ----------
  private base64UrlDecode(input: string): string {
    const padded = input.replaceAll('-', '+').replaceAll('_', '/');
    const pad = padded.length % 4;
    const final_ = pad ? padded + '===='.slice(pad) : padded;
    return atob(final_);
  }

  private decodePayload(token?: string): JwtPayload | null {
    const t = token ?? this.getToken();
    if (!t) return null;
    const parts = t.split('.');
    if (parts.length !== 3) return null;
    try {
      const json = this.base64UrlDecode(parts[1]);
      return JSON.parse(json) as JwtPayload;
    } catch {
      return null;
    }
  }

  isExpired(token?: string, skewSec = 10): boolean {
    const p = this.decodePayload(token);
    if (!p || typeof p.exp !== 'number') return false;
    const nowSec = Math.floor(Date.now() / 1000);
    return nowSec >= p.exp - skewSec;
  }

  secondsUntilExpiry(token?: string): number | null {
    const p = this.decodePayload(token);
    if (!p || typeof p.exp !== 'number') return null;
    return Math.floor(p.exp - Date.now() / 1000);
  }

  isAuthenticated(): boolean {
    const token = this.getToken();
    return !!token && !this.isExpired(token);
  }

  getRoles(): string[] {
    const p = this.decodePayload();
    if (!p) return [];
    if (Array.isArray(p.roles)) return p.roles;
    if (Array.isArray(p.authorities)) return p.authorities;
    // Keycloak shape: realm-level roles live under `realm_access.roles`.
    // Normalise to the `ROLE_*` convention the rest of the portal expects.
    const realmAccess = p['realm_access'] as { roles?: unknown } | undefined;
    if (Array.isArray(realmAccess?.roles)) {
      return realmAccess.roles
        .filter((r): r is string => typeof r === 'string')
        .map((r) => (r.startsWith('ROLE_') ? r : `ROLE_${r}`));
    }
    if (typeof p.scope === 'string') return p.scope.split(/\s+/);
    return [];
  }

  getPrimaryRole(): string | null {
    return this.getPrimaryRoleFromList(this.getRoles());
  }

  getPrimaryRoleFromList(roles?: string[] | null): string | null {
    if (!roles?.length) return null;
    let best: string | null = null;
    let bestPriority = Infinity;
    for (const role of roles) {
      const priority = AuthService.ROLE_PRIORITY.get(role) ?? Infinity;
      if (priority < bestPriority) {
        bestPriority = priority;
        best = role;
      }
    }
    return best;
  }

  getSubject(): string | null {
    return this.decodePayload()?.sub ?? null;
  }

  getUserId(): string | null {
    const p = this.decodePayload();
    if (!p) return null;
    return (p.uid as string) ?? (p.id as string) ?? null;
  }

  /**
   * Returns true when the user holds a role that is allowed to span multiple
   * hospitals (Super Admin and Hospital Admin).  All other roles (doctor, nurse,
   * receptionist, …) are always scoped to a single hospital — the one they are
   * currently signed into.
   */
  isAdminRole(): boolean {
    const roles = this.getRoles();
    return roles.some((r) => r === 'ROLE_SUPER_ADMIN' || r === 'ROLE_HOSPITAL_ADMIN');
  }

  /**
   * The working hospital: the active one when set, else the primary the
   * session last reported (stored profile). E9 #55b: the token's hospital
   * claims are never consulted — they are frozen at login while the server
   * resolves the scope live.
   */
  /**
   * The hospital the current request is scoped to: what the interceptor
   * sends as `X-Hospital-Id`. Staff get their assignment; a super-admin gets
   * the chip's pick, or `null` in global view, never the primary hospital by
   * default (design call #5), so a page keyed on this id and the header the
   * backend reads can no longer disagree.
   */
  getHospitalId(): string | null {
    return this.roleContext.effectiveHospitalIdForRequest();
  }

  hasAnyRole(expected: string[]): boolean {
    // Doctor equivalence (role audit C2): PHYSICIAN/SURGEON satisfy
    // ROLE_DOCTOR checks, mirroring the backend authority expansion.
    const roles = expandRoleEquivalents(this.getRoles());
    return expected.some((r) => roles.includes(r));
  }

  resolveLandingPath(): string {
    if (this.roleContext.activeRole === 'ROLE_SUPER_ADMIN') {
      return '/super-admin';
    }
    return '/dashboard';
  }

  /**
   * Calls GET /api/auth/session/bootstrap to retrieve authoritative session
   * context (roles, hospital, staff/patient profile) from the DB.
   *
   * Should be called once after a successful login so all hospital-context
   * decisions use DB data rather than stale JWT claims.
   */
  sessionBootstrap(): Observable<SessionBootstrapResponse> {
    return this.http.get<SessionBootstrapResponse>('auth/session/bootstrap');
  }

  logout(): void {
    this.endSession();
    this.revokeServerSession();
    this.clearLocalState();
  }

  /**
   * The local half of signing out: session-owned work stops, and the token,
   * the profile, the role context and every session-scoped key the portal
   * keeps in the browser (core/browser-storage.ts) are cleared, so the next
   * person at this browser starts from nothing.
   */
  endLocalSession(): void {
    this.endSession();
    this.clearLocalState();
  }

  private clearLocalState(): void {
    this.releaseOriginalSession();
    this.clearToken();
    this.clearRefreshToken();
    this.clearUserProfile();
    // The next user must not inherit this one's provider flag or a resolved
    // session (the shell's emergency-broadcast gate reads both).
    this.roleContext.clearSession();
    if (this.isBrowser) clearSessionScopedStorage();
  }

  /**
   * POST /api/auth/logout so the server revokes the session: the bearer it
   * carries and the HttpOnly `hms_refresh` cookie (hence `withCredentials`).
   * Clearing local storage alone left the refresh cookie able to mint a new
   * access token until it expired.
   *
   * Fire-and-forget: the local sign-out never waits on or fails because of
   * it. The token is read before local state is cleared and sent explicitly;
   * the absolute URL skips the API-prefix interceptor (whose expired-token
   * path would call logout() again), and the error interceptor never tries a
   * refresh or a second logout on this call's 401.
   */
  private revokeServerSession(): void {
    if (!this.isBrowser) return;
    const token = this.getToken();
    const headers = token ? new HttpHeaders({ Authorization: `Bearer ${token}` }) : undefined;
    this.http
      .post(`${globalThis.location.origin}/api/auth/logout`, {}, { headers, withCredentials: true })
      .pipe(catchError(() => EMPTY))
      .subscribe();
  }
}
