import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { ProviderSettings, isProviderFacilityType } from '../services/provider.model';
import { ProviderPortalService } from '../services/provider-portal.service';
import { RoleContextService } from './role-context.service';

/**
 * Whether this session acts at a provider facility (a private pharmacy or
 * laboratory), and the settings its shell needs (provider plan AC-12, §6.6).
 *
 * <p>Who is asked at all is decided from what the session bootstrap already
 * said: `providerUser`, the server's own confinement rule over the user's
 * live assignments. A hospital pharmacist or lab user is never asked, and
 * nobody is asked before the bootstrap has resolved (the guards wait for it).
 *
 * <p>The answer itself is the server's: `GET /provider/settings`, for the
 * facility the request acts at (the `X-Hospital-Id` the session sends). An
 * answer therefore belongs to one user AND one scope: switching facility asks
 * again, and one branch's answer never stands in for another's. A page that
 * gets the "not available" 404 calls {@link invalidate}: the seat went away,
 * so the shell and the guards ask again.
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
   * acts at, or `null` when that is no provider facility. Before the session
   * bootstrap has resolved this is `null` and nothing is cached or asked.
   * After it, a user the bootstrap did not mark as a provider user is never
   * asked; one it did is asked at most once per scope, a 404 (no seat there)
   * being final for that scope. Any other failure is never cached.
   */
  load(): Observable<ProviderSettings | null> {
    const key = this.key();
    if (key === null) {
      this.forget();
      return of(null);
    }
    if (!this.roleContext.sessionResolved()) {
      // Not an answer yet: the scope and the provider flag are still unknown.
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
    if (!this.roleContext.providerUser()) {
      this.answer(key, null);
      return of(null);
    }
    this.inFlightFor = key;
    this.inFlight = this.portal.settings().pipe(
      map((settings) => (isProviderFacilityType(settings?.facilityType) ? settings : null)),
      tap((settings) => this.answer(key, settings)),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 404) {
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

  /**
   * A provider page got the "not available" 404: the seat it relied on may be
   * gone (deactivated, no longer the admin, facility suspended). Drop the
   * cached answer and ask again, so the nav and the guards follow the server.
   */
  invalidate(): void {
    this.forget();
    this.load().subscribe();
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
