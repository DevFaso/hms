import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { DashboardService, DoctorResultQueueItem } from '../../services/dashboard.service';
import { currentLocale } from '../../shared/i18n/app-locale';

/** One severity bucket of the review queue, as the template renders it. */
interface LabResultGroup {
  key: 'CRITICAL' | 'ABNORMAL' | 'NORMAL';
  labelKey: string;
  badgeClass: string;
  items: DoctorResultQueueItem[];
}

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

  readonly results = signal<DoctorResultQueueItem[]>([]);
  readonly loading = signal(false);
  /** Explicit, never an empty list: a 403 or an outage must not read as "none". */
  readonly loadError = signal(false);

  /**
   * The queue split by severity, in the order the backend already sorts by.
   * Anything the backend grades outside the three-value family falls into
   * NORMAL's bucket rather than disappearing from the worklist.
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
        items: items.filter((r) => r.abnormalFlag !== 'CRITICAL' && r.abnormalFlag !== 'ABNORMAL'),
      },
    ];
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.dashboardService.getResultReviewQueue().subscribe({
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
      default:
        return 'inBasket.labNormal';
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
