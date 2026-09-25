import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { DashboardService, DoctorResultQueueItem } from '../../services/dashboard.service';
import { HospitalScopeHintComponent } from '../../shared/hospital-scope-chip/hospital-scope-hint.component';
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
 * `getResultReviewQueue` IS hospital-filtered now — `ResultReviewServiceImpl`
 * reads `findByOrderingStaff_IdAndHospital_Id` against the scope the caller
 * is acting in, and refuses with a 404 when none resolves. It still has no
 * date window and no reviewed state, so within that one hospital it returns
 * every released result this physician has ever ordered there and nothing
 * ever leaves it: the list only grows, which is the whole reason for a cap.
 * Scoping makes it grow more slowly at a multi-hospital clinician; it does
 * not bound it, and a single busy hospital is exactly where the queue is
 * longest. So the cap stays at 50, and the justification is the unbounded
 * growth, not the missing hospital filter.
 *
 * Drawn whole, a worklist becomes an un-paginated table that only grows and a
 * header count that reads as "items needing attention". The cap is applied
 * AFTER the severity split, so a critical row is never the one dropped, and
 * what was cut is stated on screen.
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
 *
 * SCOPE-DEPENDENT. The endpoint filters by the hospital the caller is acting
 * in and answers 404 when none resolves, so this category reads once per
 * scope and not once per mount: a switch from A to B that left A's released
 * results on screen under B's label is a worklist lying about whose results
 * these are, and with no scope at all the category declines to read and points
 * at the chip rather than drawing an error card over an empty table.
 */
@Component({
  selector: 'app-lab-results-inbox',
  standalone: true,
  imports: [RouterLink, TranslateModule, HospitalScopeHintComponent],
  templateUrl: './lab-results-inbox.html',
  styleUrl: './lab-results-inbox.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LabResultsInboxComponent {
  private readonly dashboardService = inject(DashboardService);
  private readonly roleContext = inject(RoleContextService);
  private readonly destroyRef = inject(DestroyRef);

  /** Which read of the queue is the current one; see load(). */
  private queueRequest = 0;

  /**
   * False for a super-admin in global view, and for any account whose
   * hospital has not resolved. The endpoint refuses that caller, so the
   * category refuses to ask.
   */
  readonly hasHospitalScope = this.roleContext.hasHospitalScope;

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

  constructor() {
    // The load is driven by the SCOPE, not by the mount: the same idiom
    // `break-glass-review` uses, because the same thing is true of both
    // reads. The effect covers the first render too — there is no ngOnInit
    // beside it, which is what keeps a scope change from firing two reads.
    //
    // The rows are dropped BEFORE the new read is issued, not when it lands.
    // Holding them until the answer arrives is what every other read on this
    // component does deliberately (a failed refresh must not take a
    // clinician's rows away), and it is exactly wrong here: those rows belong
    // to the hospital that was just left, and a slow read — or one that fails
    // — would leave the other tenant's released results on screen under this
    // hospital's heading.
    effect(() => {
      const scoped = this.roleContext.effectiveHospitalIdForRequest() != null;
      this.results.set([]);
      this.loadError.set(false);
      if (!scoped) {
        // Invalidate any read still in flight, so a response issued under the
        // previous scope cannot write its rows in after the switch.
        this.queueRequest++;
        this.loading.set(false);
        return;
      }
      this.load();
    });
  }

  load(): void {
    // The ↻ and both Retry controls are hidden without a scope, but a guard
    // here is what makes that a property of the component rather than of one
    // template: the endpoint answers 404, and a 404 drawn as "the queue could
    // not be loaded" sends a clinician looking for an outage.
    if (!this.hasHospitalScope()) return;
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
    // `loadError` is NOT cleared here. Clearing on start blanked the stale
    // notice for the whole request window, so a Retry that failed seconds
    // later showed held rows as current in between. Only a response clears it.
    this.dashboardService
      .getResultReviewQueue()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          if (!isCurrent()) return;
          this.results.set(items ?? []);
          this.loadError.set(false);
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
    // result this physician ever ordered at this hospital. Without the year, a critical result
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
