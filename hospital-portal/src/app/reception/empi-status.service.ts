import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, map, of, shareReplay } from 'rxjs';

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
  private request$: Observable<boolean> | null = null;

  /** Asks once per session; later calls share the answer. */
  load(): Observable<boolean> {
    if (!this.request$) {
      this.request$ = this.http.get<{ enabled: boolean }>('/empi/candidates/status').pipe(
        map((res) => res?.enabled === true),
        catchError(() => of(false)),
        shareReplay(1),
      );
      this.request$.subscribe((on) => this.enabled.set(on));
    }
    return this.request$;
  }
}

/** QA D987: the duplicate-patient page opens only while the matcher is on. */
export const empiEnabledGuard: CanActivateFn = () => {
  const router = inject(Router);
  return inject(EmpiStatusService)
    .load()
    .pipe(map((on) => (on ? true : router.createUrlTree(['/reception']))));
};
