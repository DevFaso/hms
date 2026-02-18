import { isPlatformBrowser } from '@angular/common';
import { Injectable, inject, PLATFORM_ID, signal } from '@angular/core';

import { RoleContextService } from '../core/role-context.service';

const ACCESS_TOKEN_KEY = 'auth_token';
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

  // ---------- User Profile ----------
  setUserProfile(profile: LoginUserProfile): void {
    if (!this.isBrowser) return;
    try {
      const toStore = { ...profile };
      if (toStore.profileImageUrl && !toStore.profileImageUrl.startsWith('http')) {
        toStore.profileImageUrl = `${globalThis.location.origin}${toStore.profileImageUrl}`;
      }
      localStorage.setItem(USER_PROFILE_KEY, JSON.stringify(toStore));
      this.currentProfile.set(toStore);
    } catch {
      // Storage error
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

  getHospitalId(): string | null {
    const ctx = this.roleContext.activeHospitalId;
    if (ctx) return ctx;
    const p = this.decodePayload();
    return p?.hospitalId ?? null;
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
    this.clearUserProfile();
  }
}
