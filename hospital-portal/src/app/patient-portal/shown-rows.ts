import { Signal, computed, signal } from '@angular/core';

/** QA D1399: rows a long patient list draws at a time. */
export const SHOWN_ROWS_STEP = 20;

/**
 * QA D1399 (D1347 class): a list whose every row was read (`allRows`), drawn a
 * step at a time with its total, so "Médicaments passés" can never again stop at
 * 20 of 249 with nothing to say so, and 340 rows do not all reach the DOM at once.
 */
export class ShownRows<T> {
  private readonly count = signal(SHOWN_ROWS_STEP);

  readonly total = computed(() => this.rows().length);
  readonly shown = computed(() => this.rows().slice(0, this.count()));
  readonly hasMore = computed(() => this.count() < this.total());

  constructor(private readonly rows: Signal<T[]>) {}

  showMore(): void {
    this.count.update((n) => n + SHOWN_ROWS_STEP);
  }
}
