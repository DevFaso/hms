import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, map, of, shareReplay } from 'rxjs';
import { backgroundRequestOptions } from '../services/ws-transport';
import { AuthService } from '../auth/auth.service';

/**
 * The roles the matcher admits: `EmpiProbabilisticController` answers
 * `/empi/candidates/status` and `/empi/candidates` to these only
 * (`ShellStartupEndpointRolesTest` keeps the two lists equal). The shell's
 * nav entry uses this list; the route's RoleGuard (`app.routes.ts`) names the
 * same five roles.
 */
export const EMPI_MATCHER_ROLES: readonly string[] = [
  'ROLE_RECEPTIONIST',
  'ROLE_NURSE',
  'ROLE_DOCTOR',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_SUPER_ADMIN',
];

/**
 * QA D987: whether the duplicate-patient matcher (EMPI probabilistic search)
 * is switched on for this deployment. When it is off the matcher answers 404,
 * so the menu entry and the page are not offered at all instead of opening on
 * "this feature is disabled".
 */
@Injectable({ providedIn: 'root' })
export class EmpiStatusService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** null until the first answer; false when off or unknown. */
  readonly enabled = signal<boolean | null>(null);
  /**
   * The last read failed (review round 16): the state is unknown, and the
   * menu offers the entry as the route guard opens the page, rather than
   * hiding it for the session after one transient error.
   */
  readonly failed = signal(false);
  private request$: Observable<boolean | null> | null = null;

  /** Asks once per session; later calls share the answer. null: not known (a failed read). */
  load(): Observable<boolean | null> {
    // QA D1012: the server refuses the probe to every other role (a patient,
    // a pharmacist, a lab role), and the refusal was re-sent on every page
    // load and reported to the audit sink. A role the matcher does not admit
    // never asks: for it the page does not exist, which is "off". The roles
    // held across the session are read, as the server's @PreAuthorize does.
    if (!this.auth.hasAnyRole([...EMPI_MATCHER_ROLES])) {
      this.enabled.set(false);
      this.failed.set(false);
      return of(false);
    }
    if (!this.request$) {
      // A background probe: the status endpoint admits the matcher's roles only,
      // and a 403 for anyone else (a pharmacist, a midwife) must not redirect
      // the shell to the forbidden page on login.
      this.request$ = this.http
        .get<{ enabled: boolean }>('/empi/candidates/status', backgroundRequestOptions())
        .pipe(
          map((res) => res?.enabled === true),
          // A failed read is "unknown", not "off": it is not kept, so the next
          // navigation asks again instead of hiding the page for the session.
          catchError(() => {
            this.request$ = null;
            return of(null);
          }),
          shareReplay(1),
        );
      this.request$.subscribe((on) => {
        this.enabled.set(on);
        this.failed.set(on === null);
      });
    }
    return this.request$;
  }
}

/**
 * QA D987: the duplicate-patient page opens only while the matcher is on.
 * Off: the dashboard, which every role reaches (not /reception, which a nurse
 * or doctor may not). Unknown (a failed read): the page opens and shows its
 * own error rather than bouncing the user away.
 */
export const empiEnabledGuard: CanActivateFn = () => {
  const router = inject(Router);
  return inject(EmpiStatusService)
    .load()
    .pipe(map((on) => (on === false ? router.createUrlTree(['/dashboard']) : true)));
};
