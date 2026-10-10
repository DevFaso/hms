import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay, tap, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { ProviderSettings, isProviderFacilityType } from '../services/provider.model';
import { ProviderPortalService } from '../services/provider-portal.service';
import { RoleContextService } from './role-context.service';

/**
 * Where the context stands: not yet answered (`pending`), acting at a
 * provider facility (`provider`), at none (`none`, the server's 404 or a
 * session the bootstrap did not mark as a provider user), or unable to tell
 * for now (`error`: a 5xx, a network failure; retried on the next ask).
 */
export type ProviderContextStatus = 'pending' | 'provider' | 'none' | 'error';

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
 * answer therefore belongs to one user, one scope AND one bootstrap verdict:
 * switching facility asks again, a re-bootstrap that turns the provider flag
 * on asks again, and one branch's answer never stands in for another's. A
 * page that gets the "not available" 404 calls {@link invalidate}: the seat
 * went away, so the shell and the guards ask again. A transient failure is
 * an error, never a "not a provider" answer, and is never cached.
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

  private readonly statusSignal = signal<ProviderContextStatus>('pending');

  /** Where the context stands; the provider pages follow it (`followFacility`). */
  readonly status = computed(() => this.statusSignal());

  /** The user, scope and bootstrap verdict the cached answer belongs to. */
  private answeredFor: string | null = null;
  private inFlight: Observable<ProviderSettings | null> | null = null;
  private inFlightFor: string | null = null;

  /**
   * The provider settings of the signed-in user at the facility the session
   * acts at, or `null` when that is no provider facility. Before the session
   * bootstrap has resolved this is `null` and nothing is cached or asked.
   * After it, a user the bootstrap did not mark as a provider user is never
   * asked; one it did is asked at most once per scope, a 404 (no seat there)
   * being final for that scope. A transient failure ERRORS the stream (the
   * status says `error`) and is not cached, so the next call asks again.
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
    // Another user's, scope's or verdict's answer never stands in while this one is asked.
    this.answeredFor = null;
    this.settings.set(null);
    this.statusSignal.set('pending');
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
          return of(null);
        }
        if (key === this.key()) {
          this.settings.set(null);
          this.statusSignal.set('error');
        }
        return throwError(() => error);
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
   * Drop the cached answer and ask again: a provider page got the "not
   * available" 404 (the seat may be gone: deactivated, no longer the admin,
   * facility suspended), or the user asked to retry after a transient failure.
   */
  invalidate(): void {
    this.forget();
    this.load().subscribe({ error: () => undefined });
  }

  /** Forget the cached answer (sign-out, or a change the server must be asked about again). */
  forget(): void {
    this.answeredFor = null;
    this.inFlight = null;
    this.inFlightFor = null;
    this.settings.set(null);
    this.statusSignal.set('pending');
  }

  private answer(key: string, settings: ProviderSettings | null): void {
    // A late answer for a scope (or verdict) the session has since left is dropped.
    if (key !== this.key()) return;
    this.answeredFor = key;
    this.settings.set(settings);
    this.statusSignal.set(settings ? 'provider' : 'none');
  }

  /**
   * `user|facility|verdict`: what one answer of the server is about; `null`
   * when nobody is signed in. The bootstrap's provider verdict is part of it,
   * so a "none" cached while it said non-provider never outlives a
   * re-bootstrap that says provider.
   */
  private key(): string | null {
    const user = this.auth.getUserProfile()?.id ?? this.auth.getSubject() ?? null;
    if (user === null) return null;
    const scope = this.roleContext.effectiveHospitalIdForRequest() ?? '';
    const verdict = this.roleContext.providerUser() ? 'provider' : 'other';
    return `${user}|${scope}|${verdict}`;
  }
}
