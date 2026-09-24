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
 * The abnormal grades this worklist understands.
 *
 * `ResultReviewServiceImpl.toQueueItem` collapses `AbnormalFlag` through
 * `.severity()` today, so only the bare `ABNORMAL` arrives — but the OTHER
 * bucket below exists for a second producer of `DoctorResultQueueItemDTO`,
 * and if one ever sends the directional values raw they must read as abnormal
 * rather than as an unknown grade ranked below plain ABNORMAL. The chart's
 * `labStatusKey` already maps both directions to Abnormal.
 */
const ABNORMAL_FLAGS: string[] = ['ABNORMAL', 'ABNORMAL_LOW', 'ABNORMAL_HIGH'];
const KNOWN_FLAGS: string[] = ['CRITICAL', ...ABNORMAL_FLAGS, 'NORMAL'];

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

  /** Which read of the queue is the current one; see load(). */
  private queueRequest = 0;

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
   * physician as "Normal".
   *
   * OTHER sits AHEAD of NORMAL, because this order is also the order
   * `visibleGroups` fills its budget in: an unrecognised grade must not be
   * what a cap drops while every normal result is drawn.
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
        items: items.filter((r) => ABNORMAL_FLAGS.includes(r.abnormalFlag)),
      },
      {
        key: 'OTHER',
        labelKey: 'inBasket.labUnknown',
        badgeClass: 'flag-badge',
        items: items.filter((r) => !KNOWN_FLAGS.includes(r.abnormalFlag)),
      },
      {
        key: 'NORMAL',
        labelKey: 'inBasket.labNormal',
        badgeClass: 'flag-badge flag-normal',
        items: items.filter((r) => r.abnormalFlag === 'NORMAL'),
      },
    ];
  });

  /**
   * The groups as drawn: severity order preserved, the list capped at
   * MAX_VISIBLE_RESULTS — except that CRITICAL is never capped.
   *
   * There is no paging and no filter here, so a capped row is not merely
   * further down the page, it is unreachable. A budget that merely fills
   * CRITICAL first still hid rows 51 onwards of a 60-critical queue, and this
   * queue has no date window and no reviewed state, so it only grows. A
   * critical released result the ordering physician cannot reach at all is
   * the one outcome this worklist exists to prevent.
   *
   * OTHER is exempt for the same reason: ordering it ahead of NORMAL is no
   * protection when fifty ABNORMAL rows come first, and a grade nobody can
   * read is not something to drop silently. The cap applies to what is
   * graded ABNORMAL or NORMAL.
   */
  readonly visibleGroups = computed<LabResultGroup[]>(() => {
    let budget = MAX_VISIBLE_RESULTS;
    return this.groups().map((group) => {
      if (group.key === 'CRITICAL' || group.key === 'OTHER') {
        budget -= group.items.length;
        return group;
      }
      const items = group.items.slice(0, Math.max(budget, 0));
      budget -= items.length;
      return { ...group, items };
    });
  });

  /** True when a row was cut — i.e. more was drawn than is on screen. */
  readonly truncated = computed(
    () =>
      this.visibleGroups().reduce((total, group) => total + group.items.length, 0) <
      this.results().length,
  );

  /** How many rows are actually drawn, for the "showing N of M" line. */
  readonly visibleCount = computed(() =>
    this.visibleGroups().reduce((total, group) => total + group.items.length, 0),
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    // Only the latest read may write. Disabling the controls is not enough on
    // its own — the ↻ is disabled while a read is in flight, but the two
    // Retry controls live in branches that are only rendered once `loading`
    // is already false, so a second read can always be started. A slow
    // failure landing after a fast success drew the stale banner over current
    // rows; a slow success landing last overwrote newer ones. Same guard the
    // chart's two lab reads and the dashboard's copy of this queue carry.
    const request = ++this.queueRequest;
    const isCurrent = (): boolean => request === this.queueRequest;
    this.loading.set(true);
    this.loadError.set(false);
    this.dashboardService
      .getResultReviewQueue()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          if (!isCurrent()) return;
          this.results.set(items ?? []);
          this.loading.set(false);
        },
        error: () => {
          if (!isCurrent()) return;
          this.loadError.set(true);
          this.loading.set(false);
        },
      });
  }

  flagClass(item: DoctorResultQueueItem): string {
    if (item.abnormalFlag === 'CRITICAL') return 'flag-badge flag-critical';
    if (ABNORMAL_FLAGS.includes(item.abnormalFlag)) return 'flag-badge flag-abnormal';
    if (item.abnormalFlag === 'NORMAL') return 'flag-badge flag-normal';
    return 'flag-badge';
  }

  flagKey(item: DoctorResultQueueItem): string {
    if (item.abnormalFlag === 'CRITICAL') return 'inBasket.labCritical';
    if (ABNORMAL_FLAGS.includes(item.abnormalFlag)) return 'inBasket.labAbnormal';
    if (item.abnormalFlag === 'NORMAL') return 'inBasket.labNormal';
    // Never "Normal" by default — the same rule the chart's labStatusKey
    // follows. An unrecognised grade says so.
    return 'inBasket.labUnknown';
  }

  /** HIGH / LOW when the backend recorded which side of the range was crossed. */
  directionKey(item: DoctorResultQueueItem): string | null {
    if (item.abnormalDirection === 'HIGH') return 'inBasket.labDirectionHigh';
    if (item.abnormalDirection === 'LOW') return 'inBasket.labDirectionLow';
    return null;
  }

  /** An em dash, as every other nullable cell in these tables renders. */
  private static readonly NO_VALUE = '—';

  formatDate(iso: string | null | undefined): string {
    if (!iso) return LabResultsInboxComponent.NO_VALUE;
    const parsed = new Date(iso);
    // A blank cell reads as a rendering fault; missing data reads as missing.
    if (Number.isNaN(parsed.getTime())) return LabResultsInboxComponent.NO_VALUE;
    // The queue has no date window and no reviewed state, so it carries every
    // result this physician ever ordered. Without the year, a critical result
    // from two years ago sits at the top of the worklist — severity sorts
    // first — reading exactly like one released this morning.
    const showYear = parsed.getFullYear() !== new Date().getFullYear();
    // No hour12 override: the locale decides, as the in-basket panel does.
    return parsed.toLocaleString(currentLocale(), {
      ...(showYear ? { year: 'numeric' } : {}),
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }
}
