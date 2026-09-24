import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { DashboardService, DoctorResultQueueItem } from '../../services/dashboard.service';
import { currentLocale } from '../../shared/i18n/app-locale';

/** One severity bucket of the review queue, as the template renders it. */
interface LabResultGroup {
  key: 'CRITICAL' | 'ABNORMAL' | 'NORMAL' | 'OTHER';
  labelKey: string;
  badgeClass: string;
  items: DoctorResultQueueItem[];
}

/**
 * How many rows the category draws.
 *
 * `getResultReviewQueue` has no date window, no hospital filter and no
 * reviewed state, so it returns every released result this physician has ever
 * ordered. Drawn whole, a worklist becomes an un-paginated table that only
 * grows and a header count that reads as "items needing attention". The cap
 * is applied AFTER the severity split, so a critical row is never the one
 * dropped, and what was cut is stated on screen.
 */
const MAX_VISIBLE_RESULTS = 50;

/**
 * B7 — the lab-results category of the clinical in-basket.
 *
 * Wave 1 made the laboratory release results deliberately and advanced the
 * order as they went, but on the web a released result landed in no worklist:
 * the in-basket panel's RESULT filter reads `in_basket_items`, and nothing in
 * the backend ever writes a row of that type. This category reads the queue
 * that IS fed — `GET /me/results/review-queue`, which lists the released
 * results of the orders the signed-in physician placed, on orders that have
 * reached RESULTED, VERIFIED or COMPLETED.
 *
 * Read-only by design: the queue carries no "reviewed" state, so there is no
 * action here that would take a row off it (see the PR body).
 */
@Component({
  selector: 'app-lab-results-inbox',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './lab-results-inbox.html',
  styleUrl: './lab-results-inbox.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LabResultsInboxComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);

  /** Exposed for the "showing N of M" line. */
  readonly maxVisible = MAX_VISIBLE_RESULTS;

  readonly results = signal<DoctorResultQueueItem[]>([]);
  readonly loading = signal(false);
  /** Explicit, never an empty list: a 403 or an outage must not read as "none". */
  readonly loadError = signal(false);

  /**
   * The queue split by severity, in the order the backend already sorts by.
   *
   * The NORMAL bucket matches the flag EXACTLY. A grade outside the
   * three-value family — which `AbnormalFlag.severity()` cannot produce today,
   * but a second producer of `DoctorResultQueueItemDTO` could — lands in
   * OTHER, labelled as unknown, rather than being shown to the ordering
   * physician as "Normal". Nothing is ever dropped from the worklist.
   */
  readonly groups = computed<LabResultGroup[]>(() => {
    const items = this.results();
    return [
      {
        key: 'CRITICAL',
        labelKey: 'inBasket.labCritical',
        badgeClass: 'flag-badge flag-critical',
        items: items.filter((r) => r.abnormalFlag === 'CRITICAL'),
      },
      {
        key: 'ABNORMAL',
        labelKey: 'inBasket.labAbnormal',
        badgeClass: 'flag-badge flag-abnormal',
        items: items.filter((r) => r.abnormalFlag === 'ABNORMAL'),
      },
      {
        key: 'NORMAL',
        labelKey: 'inBasket.labNormal',
        badgeClass: 'flag-badge flag-normal',
        items: items.filter((r) => r.abnormalFlag === 'NORMAL'),
      },
      {
        key: 'OTHER',
        labelKey: 'inBasket.labUnknown',
        badgeClass: 'flag-badge',
        items: items.filter(
          (r) =>
            r.abnormalFlag !== 'CRITICAL' &&
            r.abnormalFlag !== 'ABNORMAL' &&
            r.abnormalFlag !== 'NORMAL',
        ),
      },
    ];
  });

  /**
   * The groups as drawn: severity order preserved, the whole list capped at
   * MAX_VISIBLE_RESULTS. CRITICAL is filled first, so the cap can only ever
   * eat into the least urgent tail.
   */
  readonly visibleGroups = computed<LabResultGroup[]>(() => {
    let budget = MAX_VISIBLE_RESULTS;
    return this.groups().map((group) => {
      const items = group.items.slice(0, Math.max(budget, 0));
      budget -= items.length;
      return { ...group, items };
    });
  });

  readonly truncated = computed(() => this.results().length > MAX_VISIBLE_RESULTS);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.dashboardService
      .getResultReviewQueue()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          this.results.set(items ?? []);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set(true);
          this.loading.set(false);
        },
      });
  }

  flagClass(item: DoctorResultQueueItem): string {
    switch (item.abnormalFlag) {
      case 'CRITICAL':
        return 'flag-badge flag-critical';
      case 'ABNORMAL':
        return 'flag-badge flag-abnormal';
      case 'NORMAL':
        return 'flag-badge flag-normal';
      default:
        return 'flag-badge';
    }
  }

  flagKey(item: DoctorResultQueueItem): string {
    switch (item.abnormalFlag) {
      case 'CRITICAL':
        return 'inBasket.labCritical';
      case 'ABNORMAL':
        return 'inBasket.labAbnormal';
      case 'NORMAL':
        return 'inBasket.labNormal';
      default:
        // Never "Normal" by default — the same rule the chart's labStatusKey
        // follows. An unrecognised grade says so.
        return 'inBasket.labUnknown';
    }
  }

  /** HIGH / LOW when the backend recorded which side of the range was crossed. */
  directionKey(item: DoctorResultQueueItem): string | null {
    if (item.abnormalDirection === 'HIGH') return 'inBasket.labDirectionHigh';
    if (item.abnormalDirection === 'LOW') return 'inBasket.labDirectionLow';
    return null;
  }

  formatDate(iso: string | null | undefined): string {
    if (!iso) return '';
    const parsed = new Date(iso);
    if (Number.isNaN(parsed.getTime())) return '';
    // No hour12 override: the locale decides, as the in-basket panel does.
    return parsed.toLocaleString(currentLocale(), {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }
}
