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

/**
 * Whether this session acts at a provider facility (a private pharmacy or
 * laboratory), and the settings its shell needs (provider plan AC-12, §6.6).
 *
 * <p>The answer is the server's: `GET /provider/settings`, which a user with
 * no seat at a provider facility gets as a 404 identical to an unmapped path.
 * It is asked only for users holding a role a provider may hold (PROVIDER_ADMIN,
 * PHARMACIST, a LAB_* role), once per signed-in user; a hospital pharmacist
 * pays one request and then sees the hospital shell as before.
 *
 * <p>UX only. The backend confines every provider request to its allow-list
 * whatever the portal shows.
 */
@Injectable({ providedIn: 'root' })
export class ProviderContextService {
  private readonly portal = inject(ProviderPortalService);
  private readonly auth = inject(AuthService);

  /** The settings when the session acts at a provider facility; `null` otherwise or until known. */
  readonly settings = signal<ProviderSettings | null>(null);

  /** True once the server has said this session acts at a provider facility. */
  readonly isProvider = computed(() => isProviderFacilityType(this.settings()?.facilityType));

  /** The user the cached answer belongs to. */
  private answeredFor: string | null = null;
  private inFlight: Observable<ProviderSettings | null> | null = null;
  private inFlightFor: string | null = null;

  /**
   * The provider settings of the signed-in user, or `null` when they act at
   * no provider facility. Asked of the server at most once per user: a 404
   * (no seat) is a final answer; any other failure is not cached, so the
   * next navigation asks again.
   */
  load(): Observable<ProviderSettings | null> {
    const user = this.userKey();
    if (user === null) {
      this.forget();
      return of(null);
    }
    if (this.answeredFor === user) {
      return of(this.settings());
    }
    if (this.inFlight && this.inFlightFor === user) {
      return this.inFlight;
    }
    // Another user's answer never stands in while this one's is asked.
    this.settings.set(null);
    if (!this.auth.hasAnyRole(PROVIDER_CAPABLE_ROLES)) {
      this.answer(user, null);
      return of(null);
    }
    this.inFlightFor = user;
    this.inFlight = this.portal.settings().pipe(
      map((settings) => (isProviderFacilityType(settings?.facilityType) ? settings : null)),
      tap((settings) => this.answer(user, settings)),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 404) {
          this.answer(user, null);
        } else {
          this.settings.set(null);
        }
        return of(null);
      }),
      finalize(() => {
        this.inFlight = null;
        this.inFlightFor = null;
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

  private answer(user: string, settings: ProviderSettings | null): void {
    this.answeredFor = user;
    this.settings.set(settings);
  }

  private userKey(): string | null {
    return this.auth.getUserProfile()?.id ?? this.auth.getSubject() ?? null;
  }
}
