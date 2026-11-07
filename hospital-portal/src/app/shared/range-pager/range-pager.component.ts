import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';

/**
 * QA D1379 / D1380 / D1390 (the D1347 class): a list paged on the server, with
 * the SERVER's total on screen. `/my-vitals`, the chart review tabs, the
 * doctor timeline and the slot admin list each showed the rows of one capped
 * read as if they were all of them. This pager says "Showing 21–40 of 667"
 * from the server's count and moves page by page, so every row is reachable.
 *
 * It renders nothing while everything fits on one page.
 */
@Component({
  selector: 'app-range-pager',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (total() > 0) {
      <nav class="range-pager" [attr.aria-label]="label() || null" [attr.data-testid]="testId()">
        <span class="range-pager__info" aria-live="polite">
          {{ 'COMMON.SHOWING_RANGE' | translate: { from: from(), to: to(), total: total() } }}
        </span>
        @if (totalPages() > 1) {
          <span class="range-pager__controls">
            <button
              type="button"
              class="range-pager__btn"
              [disabled]="busy() || page() <= 0"
              (click)="go(page() - 1)"
              [attr.data-testid]="testId() + '-prev'"
            >
              <span class="material-symbols-outlined" aria-hidden="true">chevron_left</span>
              {{ 'COMMON.PREVIOUS' | translate }}
            </button>
            <button
              type="button"
              class="range-pager__btn"
              [disabled]="busy() || page() + 1 >= totalPages()"
              (click)="go(page() + 1)"
              [attr.data-testid]="testId() + '-next'"
            >
              {{ 'COMMON.NEXT' | translate }}
              <span class="material-symbols-outlined" aria-hidden="true">chevron_right</span>
            </button>
          </span>
        }
      </nav>
    }
  `,
  styles: [
    `
      .range-pager {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 0.75rem;
        padding: 0.75rem 0;
      }
      .range-pager__info {
        font-size: 0.8125rem;
        color: var(--text-secondary, #64748b);
      }
      .range-pager__controls {
        display: inline-flex;
        gap: 0.5rem;
      }
      .range-pager__btn {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        min-height: 32px;
        padding: 0 0.625rem;
        border: 1px solid var(--border, #e2e8f0);
        border-radius: 7px;
        background: #fff;
        color: var(--text-primary, #1e293b);
        font-size: 0.8125rem;
        cursor: pointer;
      }
      .range-pager__btn:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
      .range-pager__btn:focus-visible {
        outline: var(--focus-ring, 2px solid #0e7c6b);
        outline-offset: var(--focus-ring-offset, 2px);
      }
      .range-pager__btn .material-symbols-outlined {
        font-size: 1.125rem;
      }
    `,
  ],
})
export class RangePagerComponent {
  /** 0-based page shown. */
  readonly page = input.required<number>();
  /** Rows per page the server answered with. */
  readonly size = input.required<number>();
  /** The server's count of every row (never the rows on hand). */
  readonly total = input.required<number>();
  /** Rows actually on this page (the last page is shorter). */
  readonly shown = input<number | null>(null);
  /** A page request is in flight. */
  readonly busy = input(false);
  readonly label = input<string>('');
  readonly testId = input<string>('range-pager');

  readonly pageChange = output<number>();

  protected readonly totalPages = computed(() => {
    const size = Math.max(1, this.size());
    return Math.max(1, Math.ceil(Math.max(0, this.total()) / size));
  });

  protected readonly from = computed(() =>
    Math.min(this.total(), this.page() * Math.max(1, this.size()) + 1),
  );

  protected readonly to = computed(() => {
    const start = this.page() * Math.max(1, this.size());
    const shown = this.shown();
    const end = shown === null ? start + this.size() : start + shown;
    return Math.min(this.total(), Math.max(end, this.from()));
  });

  protected go(target: number): void {
    if (target < 0 || target >= this.totalPages() || target === this.page()) return;
    this.pageChange.emit(target);
  }
}
