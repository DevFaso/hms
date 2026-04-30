import {
  ChangeDetectionStrategy,
  Component,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription, takeUntil } from 'rxjs';

import { ChartReview, ChartReviewService } from '../../services/chart-review.service';

type LoadState = 'loading' | 'ready' | 'empty' | 'error';

export type ChartReviewTab =
  | 'timeline'
  | 'encounters'
  | 'notes'
  | 'results'
  | 'medications'
  | 'imaging'
  | 'procedures';

const TABS: readonly ChartReviewTab[] = [
  'timeline',
  'encounters',
  'notes',
  'results',
  'medications',
  'imaging',
  'procedures',
];

const ABNORMAL_DANGER = new Set(['CRITICAL', 'ABNORMAL']);

/**
 * Epic-style Chart Review tabbed viewer mounted on the patient chart.
 * Renders the six clinical sections plus a unified timeline from a single
 * aggregated payload. Like the Storyboard banner, it cancels in-flight
 * requests on patient/hospital change so a slow stale response can never
 * paint over a newer one — a clinical-safety hazard if the chart shows
 * the wrong patient's data.
 */
@Component({
  selector: 'app-chart-review',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chart-review.component.html',
  styleUrl: './chart-review.component.scss',
})
export class ChartReviewComponent implements OnChanges, OnDestroy {
  readonly patientId = input<string | null | undefined>(null);
  readonly hospitalId = input<string | null | undefined>(null);
  readonly limit = input<number | null | undefined>(null);

  protected readonly state = signal<LoadState>('loading');
  protected readonly chart = signal<ChartReview | null>(null);
  protected readonly activeTab = signal<ChartReviewTab>('timeline');

  protected readonly tabs = TABS;

  protected readonly counts = computed(() => {
    const c = this.chart();
    return {
      timeline: c?.timeline?.length ?? 0,
      encounters: c?.encounters?.length ?? 0,
      notes: c?.notes?.length ?? 0,
      results: c?.results?.length ?? 0,
      medications: c?.medications?.length ?? 0,
      imaging: c?.imaging?.length ?? 0,
      procedures: c?.procedures?.length ?? 0,
    };
  });

  private readonly chartReviewService = inject(ChartReviewService);
  private readonly destroyed$ = new Subject<void>();
  private inFlight?: Subscription;

  ngOnChanges(_changes: SimpleChanges): void {
    const id = this.patientId();
    if (!id) {
      this.cancelInFlight();
      this.chart.set(null);
      this.state.set('empty');
      return;
    }
    this.load(id, this.hospitalId() ?? undefined, this.limit() ?? undefined);
  }

  ngOnDestroy(): void {
    this.cancelInFlight();
    this.destroyed$.next();
    this.destroyed$.complete();
  }

  protected setTab(tab: ChartReviewTab): void {
    this.activeTab.set(tab);
  }

  protected resultClass(flag?: string | null): string {
    if (!flag) return 'chart-review__pill--neutral';
    const upper = flag.toUpperCase();
    if (ABNORMAL_DANGER.has(upper)) return 'chart-review__pill--danger';
    return 'chart-review__pill--info';
  }

  protected statusClass(status?: string | null): string {
    if (!status) return 'chart-review__pill--neutral';
    const upper = status.toUpperCase();
    if (upper.includes('CANCEL') || upper.includes('REJECT') || upper.includes('FAIL')) {
      return 'chart-review__pill--danger';
    }
    if (upper.includes('PROGRESS') || upper.includes('ACTIVE') || upper.includes('SIGNED')) {
      return 'chart-review__pill--info';
    }
    if (upper.includes('PENDING') || upper.includes('DRAFT') || upper.includes('SCHEDULED')) {
      return 'chart-review__pill--warning';
    }
    return 'chart-review__pill--neutral';
  }

  protected sectionLabelKey(section: string): string {
    return `CHART_REVIEW.SECTION.${section}`;
  }

  /**
   * Cancel any prior request before issuing the next one. This is the same
   * guard the Storyboard banner uses and exists for the same reason: a stale
   * response landing after a newer patient is selected would paint the wrong
   * patient's chart.
   */
  private load(patientId: string, hospitalId?: string, limit?: number): void {
    this.cancelInFlight();
    this.state.set('loading');
    this.inFlight = this.chartReviewService
      .getChartReview(patientId, hospitalId, limit)
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (c) => {
          this.chart.set(c ?? null);
          const empty =
            !c ||
            ((c.encounters?.length ?? 0) === 0 &&
              (c.notes?.length ?? 0) === 0 &&
              (c.results?.length ?? 0) === 0 &&
              (c.medications?.length ?? 0) === 0 &&
              (c.imaging?.length ?? 0) === 0 &&
              (c.procedures?.length ?? 0) === 0);
          this.state.set(empty ? 'empty' : 'ready');
        },
        error: () => {
          this.chart.set(null);
          this.state.set('error');
        },
      });
  }

  private cancelInFlight(): void {
    this.inFlight?.unsubscribe();
    this.inFlight = undefined;
  }
}
