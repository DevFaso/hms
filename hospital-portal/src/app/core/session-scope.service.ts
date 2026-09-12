import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

import { AuthService, LoginUserProfile, SessionBootstrapResponse } from '../auth/auth.service';
import { RoleContextService } from './role-context.service';

/** Login-response fields the session bootstrap does not carry, kept on the stored profile. */
export interface ProfileExtras {
  forcePasswordChange?: boolean;
  forceUsernameChange?: boolean;
  phoneNumber?: string;
  licenseNumber?: string;
}

/**
 * E9 #55b — the portal's hospital scope comes from the session, never the token.
 *
 * The JWT carries `permittedHospitalIds` / `primaryHospitalId` as they were at
 * login and is never refreshed, while the server (E9 #55, PR #597) resolves
 * both from the live assignment table on every request. The portal used to
 * decode the stale claims on every bootstrap and, for non-admin roles, collapse
 * the list to ONE hospital — so the picker showed a set the server no longer
 * honoured. This is the single hydration path: `GET /auth/session/bootstrap`
 * on app start, login, MFA completion, the OIDC redirect and impersonation
 * start/stop. The stored profile is the last live answer and the only
 * fallback when the server cannot be asked.
 */
@Injectable({ providedIn: 'root' })
export class SessionScopeService {
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);

  /**
   * Ask the server and apply what it says. When it cannot be asked the stored
   * profile stands in and the stream emits null, so callers can fall back to a
   * DB-fresh body they already hold (the login response) without touching the
   * token.
   */
  hydrate(extras: ProfileExtras = {}): Observable<SessionBootstrapResponse | null> {
    return this.auth.sessionBootstrap().pipe(
      map((bootstrap) => {
        this.applyBootstrap(bootstrap, extras);
        return bootstrap;
      }),
      catchError(() => {
        this.applyStoredProfile();
        return of(null);
      }),
    );
  }

  /**
   * Apply an authoritative bootstrap: the roles, the permitted set exactly as
   * the server states it (never collapsed by role), the active hospital, the
   * super-admin global default, and the stored profile that later bootstraps
   * fall back to.
   */
  applyBootstrap(bootstrap: SessionBootstrapResponse, extras: ProfileExtras = {}): void {
    const roles = bootstrap.roles?.length ? bootstrap.roles : this.auth.getRoles();
    this.roleContext.setRoles(roles);
    const permitted = (bootstrap.permittedHospitalIds ?? []).filter((id) => !!id);
    this.applyScope(permitted, bootstrap.primaryHospitalId ?? null);
    this.roleContext.markSuperAdminGlobalDefaults();

    const previous = this.auth.getUserProfile();
    const profile: LoginUserProfile = {
      id: bootstrap.userId,
      username: bootstrap.username,
      email: bootstrap.email ?? '',
      firstName: bootstrap.firstName,
      lastName: bootstrap.lastName,
      phoneNumber: extras.phoneNumber ?? previous?.phoneNumber,
      profileImageUrl: bootstrap.profileImageUrl,
      roles,
      profileType: bootstrap.staffId ? 'STAFF' : bootstrap.patientId ? 'PATIENT' : undefined,
      licenseNumber: extras.licenseNumber ?? previous?.licenseNumber,
      staffId: bootstrap.staffId,
      roleName: bootstrap.staffRoleCode,
      active: true,
      forcePasswordChange: extras.forcePasswordChange ?? previous?.forcePasswordChange,
      forceUsernameChange: extras.forceUsernameChange ?? previous?.forceUsernameChange,
      primaryHospitalId: bootstrap.primaryHospitalId,
      primaryHospitalName: bootstrap.primaryHospitalName,
      hospitalIds: permitted,
    };
    this.auth.setUserProfile(profile);
  }

  /**
   * The stored profile is the last thing the server said; apply it when the
   * server cannot be asked (offline bootstrap, a failed request). Roles still
   * come from the token here — the route guards need them synchronously and
   * the item is about the hospital scope, which never comes from the token.
   */
  applyStoredProfile(): void {
    const roles = this.auth.getRoles();
    if (roles.length > 0) {
      this.roleContext.setRoles(roles);
    }
    const profile = this.auth.getUserProfile();
    this.applyScope(
      (profile?.hospitalIds ?? []).filter((id) => !!id),
      profile?.primaryHospitalId ?? null,
    );
    this.roleContext.markSuperAdminGlobalDefaults();
  }

  /**
   * The permitted set and the active hospital, in this order of preference:
   * the only permitted hospital; the primary when it is permitted (or nothing
   * is listed); the current active hospital when still permitted; the first
   * permitted; none.
   */
  applyScope(permitted: string[], primary: string | null): void {
    this.roleContext.setPermittedHospitalIds(permitted);
    const current = this.roleContext.activeHospitalId;
    let active: string | null;
    if (permitted.length === 1) {
      active = permitted[0];
    } else if (primary && (permitted.length === 0 || permitted.includes(primary))) {
      active = primary;
    } else if (current && permitted.includes(current)) {
      active = current;
    } else {
      active = permitted[0] ?? null;
    }
    this.roleContext.activeHospitalId = active;
  }
}
