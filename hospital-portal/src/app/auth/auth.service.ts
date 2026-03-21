import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject, PLATFORM_ID, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { RoleContextService } from '../core/role-context.service';

const ACCESS_TOKEN_KEY = 'auth_token';
const REFRESH_TOKEN_KEY = 'auth_refresh_token';
const USER_PROFILE_KEY = 'user_profile';

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
  /** All hospital IDs this user is permitted to access. */
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
    'ROLE_LAB_TECHNICIAN',
    'ROLE_LAB_MANAGER',
    'ROLE_STAFF',
    'ROLE_PATIENT',
  ];

  private static readonly ROLE_PRIORITY = new Map(
    AuthService.ROLE_HIERARCHY.map((role, index) => [role, index]),
  );

  private readonly roleContext = inject(RoleContextService);
  private readonly platformId = inject(PLATFORM_ID);
  private readonly http = inject(HttpClient);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** Reactive signal that updates whenever setUserProfile() is called. */
  readonly currentProfile = signal<LoginUserProfile | null>(null);

  // ---------- Storage ----------
  getToken(): string | null {
    if (!this.isBrowser) return null;
    try {
      return (
        localStorage.getItem(ACCESS_TOKEN_KEY) || sessionStorage.getItem(ACCESS_TOKEN_KEY) || null
      );
    } catch {
      return null;
    }
  }

  setToken(token: string, remember = true): void {
    if (!this.isBrowser) return;
    try {
      if (remember) {
        localStorage.setItem(ACCESS_TOKEN_KEY, token);
      } else {
        sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
      }
    } catch {
      // Storage not available
    }
  }

  clearToken(): void {
    if (!this.isBrowser) return;
    try {
      localStorage.removeItem(ACCESS_TOKEN_KEY);
      sessionStorage.removeItem(ACCESS_TOKEN_KEY);
    } catch {
      // Storage not available
    }
  }

  // ---------- Refresh Token ----------
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

  setRefreshToken(token: string, remember = true): void {
    if (!this.isBrowser) return;
    try {
      if (remember) {
        localStorage.setItem(REFRESH_TOKEN_KEY, token);
      } else {
        sessionStorage.setItem(REFRESH_TOKEN_KEY, token);
      }
    } catch {
      // Storage not available
    }
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
   * Call POST /api/auth/token/refresh with the stored refresh token.
   * Returns an Observable of the new token pair.
   *
   * Uses a window-relative absolute URL so the request bypasses the
   * apiPrefixInterceptor (which would add a double /api prefix).
   * Note: Angular interceptors still run for same-origin absolute URLs,
   * so the csrfInterceptor will execute — this is harmless since the
   * endpoint is authenticated by the signed refresh-token body.
   */
  refreshTokenRequest(): Observable<{ accessToken: string; refreshToken: string }> {
    const refreshToken = this.getRefreshToken();
    // Build an absolute URL at runtime so we always resolve against the current
    // origin without duplicating the /api prefix that apiPrefixInterceptor adds.
    const baseUrl = this.isBrowser ? `${globalThis.location.origin}/api` : '/api';
    return this.http.post<{ accessToken: string; refreshToken: string }>(
      `${baseUrl}/auth/token/refresh`,
      { refreshToken },
    );
  }

  // ---------- User Profile ----------
  setUserProfile(profile: LoginUserProfile): void {
    if (!this.isBrowser) return;
    try {
      const toStore = { ...profile };
      if (toStore.profileImageUrl) {
        toStore.profileImageUrl = this.normalizeImageUrl(toStore.profileImageUrl);
      }
      localStorage.setItem(USER_PROFILE_KEY, JSON.stringify(toStore));
      this.currentProfile.set(toStore);
    } catch {
      // Storage error
    }
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
    try {
      const raw = localStorage.getItem(USER_PROFILE_KEY);
      const profile = raw ? (JSON.parse(raw) as LoginUserProfile) : null;
      if (profile && !this.currentProfile()) {
        this.currentProfile.set(profile);
      }
      return profile;
    } catch {
      return null;
    }
  }

  clearUserProfile(): void {
    if (!this.isBrowser) return;
    try {
      localStorage.removeItem(USER_PROFILE_KEY);
      this.currentProfile.set(null);
    } catch {
      // Storage error
    }
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

  formatRole(role: string): string {
    return role
      .replace(/^ROLE_/, '')
      .replaceAll('_', ' ')
      .replaceAll(/\b\w/g, (c) => c.toUpperCase());
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

  getHospitalId(): string | null {
    const ctx = this.roleContext.activeHospitalId;
    if (ctx) return ctx;
    const p = this.decodePayload();
    return (p?.['primaryHospitalId'] as string) ?? (p?.hospitalId as string) ?? null;
  }

  /**
   * Returns the hospital IDs this user is permitted to access.
   *
   * - Admin roles (SUPER_ADMIN, HOSPITAL_ADMIN): returns the full `hospitalIds[]`
   *   array from the JWT so they can manage/switch between hospitals.
   * - All other roles (receptionist, doctor, nurse, …): always returns only
   *   `[primaryHospitalId]`.  These users are locked to the hospital they signed
   *   into and must never see or select a different one.
   */
  getPermittedHospitalIds(): string[] {
    const p = this.decodePayload();
    if (!p) return [];

    const primary = (p['primaryHospitalId'] as string) ?? (p.hospitalId as string) ?? null;

    // Non-admin staff are always locked to exactly one hospital — their primary.
    if (!this.isAdminRole()) {
      return primary ? [primary] : [];
    }

    // Admin roles get the full list so they can operate across hospitals.
    const raw = p['hospitalIds'];
    if (Array.isArray(raw)) {
      const list = raw.filter((v): v is string => typeof v === 'string');
      if (list.length > 0) return list;
    }
    return primary ? [primary] : [];
  }

  hasAnyRole(expected: string[]): boolean {
    const roles = this.getRoles();
    return expected.some((r) => roles.includes(r));
  }

  resolveLandingPath(): string {
    return '/dashboard';
  }

  logout(): void {
    this.clearToken();
    this.clearRefreshToken();
    this.clearUserProfile();
    // Clear idle lock state so the lock screen doesn't appear on next login
    if (this.isBrowser) {
      try {
        sessionStorage.removeItem('hms_idle_locked');
        sessionStorage.removeItem('hms_lock_ts');
      } catch {
        /* storage not available */
      }
    }
  }
}
