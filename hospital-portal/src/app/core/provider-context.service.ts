import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import {
  PROVIDER_CAPABLE_ROLES,
  ProviderSettings,
  isProviderFacilityType,
} from '../services/provider.model';
import { ProviderPortalService } from '../services/provider-portal.service';
import { RoleContextService } from './role-context.service';

/**
 * Whether this session acts at a provider facility (a private pharmacy or
 * laboratory), and the settings its shell needs (provider plan AC-12, §6.6).
 *
 * <p>The answer is the server's: `GET /provider/settings`, which a user with
 * no seat at a provider facility gets as a 404 identical to an unmapped path.
 * The server answers for the facility the request acts at (the
 * `X-Hospital-Id` the session sends), so an answer belongs to one user AND
 * one scope: switching facility asks again, and one branch's answer never
 * stands in for another's. It is asked only for users holding a role a
 * provider may hold (PROVIDER_ADMIN, PHARMACIST, a LAB_* role).
 *
 * <p>UX only. The backend confines every provider request to its allow-list
 * whatever the portal shows.
 */
@Injectable({ providedIn: 'root' })
export class ProviderContextService {
  private readonly portal = inject(ProviderPortalService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);

  /** The settings when the session acts at a provider facility; `null` otherwise or until known. */
  readonly settings = signal<ProviderSettings | null>(null);

  /** True once the server has said this session acts at a provider facility. */
  readonly isProvider = computed(() => isProviderFacilityType(this.settings()?.facilityType));

  /** The user and scope the cached answer belongs to. */
  private answeredFor: string | null = null;
  private inFlight: Observable<ProviderSettings | null> | null = null;
  private inFlightFor: string | null = null;

  /**
   * The provider settings of the signed-in user at the facility the session
   * acts at, or `null` when that is no provider facility. Asked of the server
   * at most once per user and scope. A 404 (no seat there) is final only once
   * the session's scope is resolved: before that the scope may still change.
   * Any other failure is never cached, so the next call asks again.
   */
  load(): Observable<ProviderSettings | null> {
    const key = this.key();
    if (key === null) {
      this.forget();
      return of(null);
    }
    if (this.answeredFor === key) {
      return of(this.settings());
    }
    if (this.inFlight && this.inFlightFor === key) {
      return this.inFlight;
    }
    // Another user's, or another facility's, answer never stands in while this one is asked.
    this.answeredFor = null;
    this.settings.set(null);
    if (!this.auth.hasAnyRole(PROVIDER_CAPABLE_ROLES)) {
      this.answer(key, null);
      return of(null);
    }
    const resolvedWhenAsked = this.roleContext.sessionResolved();
    this.inFlightFor = key;
    this.inFlight = this.portal.settings().pipe(
      map((settings) => (isProviderFacilityType(settings?.facilityType) ? settings : null)),
      tap((settings) => this.answer(key, settings)),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 404 && resolvedWhenAsked) {
          this.answer(key, null);
        } else if (key === this.key()) {
          this.settings.set(null);
        }
        return of(null);
      }),
      finalize(() => {
        if (this.inFlightFor === key) {
          this.inFlight = null;
          this.inFlightFor = null;
        }
      }),
      shareReplay(1),
    );
    return this.inFlight;
  }

  /** Forget the cached answer (sign-out, or a change the server must be asked about again). */
  forget(): void {
    this.answeredFor = null;
    this.inFlight = null;
    this.inFlightFor = null;
    this.settings.set(null);
  }

  private answer(key: string, settings: ProviderSettings | null): void {
    // A late answer for a scope the session has since left is dropped.
    if (key !== this.key()) return;
    this.answeredFor = key;
    this.settings.set(settings);
  }

  /** `user|facility`: what one answer of the server is about; `null` when nobody is signed in. */
  private key(): string | null {
    const user = this.auth.getUserProfile()?.id ?? this.auth.getSubject() ?? null;
    if (user === null) return null;
    return `${user}|${this.roleContext.effectiveHospitalIdForRequest() ?? ''}`;
  }
}
