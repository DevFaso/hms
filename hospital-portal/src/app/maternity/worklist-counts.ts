import { DestroyRef, inject, signal } from '@angular/core';
import { catchError, defer, Observable, of, Subscription } from 'rxjs';

/** Independent counts: failures are unknown, never an empty worklist. */
export function worklistCounts<K extends string>() {
  const values = signal<Partial<Record<K, number | null>>>({});
  let pending = new Subscription();
  let generation = 0;
  const owner = inject(DestroyRef);
  owner.onDestroy(() => {
    generation++;
    pending.unsubscribe();
  });
  return {
    values: values.asReadonly(),
    load(requests: Partial<Record<K, () => Observable<number>>>): void {
      if (owner.destroyed) return;
      const current = ++generation;
      pending.unsubscribe();
      pending = new Subscription();
      values.set({});
      for (const key of Object.keys(requests) as K[]) {
        const request = requests[key];
        if (!request) continue;
        pending.add(
          defer(request)
            .pipe(catchError(() => of(null)))
            .subscribe((count) => {
              if (current !== generation) return;
              const valid = count !== null && Number.isInteger(count) && count >= 0;
              values.update((previous) => ({ ...previous, [key]: valid ? count : null }));
            }),
        );
      }
    },
  };
}
