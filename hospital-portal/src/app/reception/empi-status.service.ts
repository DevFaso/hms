import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, map, of, shareReplay } from 'rxjs';
import { backgroundRequestOptions } from '../services/ws-transport';

/**
 * QA D987: whether the duplicate-patient matcher (EMPI probabilistic search)
 * is switched on for this deployment. When it is off the matcher answers 404,
 * so the menu entry and the page are not offered at all instead of opening on
 * "this feature is disabled".
 */
@Injectable({ providedIn: 'root' })
export class EmpiStatusService {
  private readonly http = inject(HttpClient);

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
