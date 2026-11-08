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
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { LabCorrectionBadgeComponent } from '../../lab/shared/lab-correction-badge.component';
import { Subject, Subscription, takeUntil } from 'rxjs';

import {
  ChartReview,
  ChartReviewEncounter,
  ChartReviewImaging,
  ChartReviewNote,
  ChartReviewSection,
  ChartReviewService,
} from '../../services/chart-review.service';
import { RangePagerComponent } from '../../shared/range-pager/range-pager.component';
import { EnumLabelService } from '../../core/enum-label.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { MedicationRoutePipe } from '../../shared/pipes/medication-route.pipe';
import { LabValuePipe } from '../../lab/shared/lab-value.pipe';
import { CredentialsLabelPipe } from '../../shared/pipes/credentials-label.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';

type LoadState = 'loading' | 'ready' | 'empty' | 'error';

export type ChartReviewTab =
  'timeline' | 'encounters' | 'notes' | 'results' | 'medications' | 'imaging' | 'procedures';

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

/** QA D1380: the list of the payload each tab shows. */
type TabRows = Pick<
  ChartReview,
  'timeline' | 'encounters' | 'notes' | 'results' | 'medications' | 'imaging' | 'procedures'
>;

const FIRST_PAGES: Record<ChartReviewTab, number> = {
  timeline: 0,
  encounters: 0,
  notes: 0,
  results: 0,
  medications: 0,
  imaging: 0,
  procedures: 0,
};

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
  imports: [
    DateTimePipe,
    LabCorrectionBadgeComponent,
    ServerTimePipe,
    CommonModule,
    TranslateModule,
    EnumLabelPipe,
    MedicationRoutePipe,
    LabValuePipe,
    CredentialsLabelPipe,
    RangePagerComponent,
  ],
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

  /** QA D1380: the page each tab shows (0-based). */
  protected readonly tabPage = signal<Record<ChartReviewTab, number>>({ ...FIRST_PAGES });
  /** QA D1380: a further page of the active tab is loading or failed. */
  protected readonly tabLoading = signal(false);
  protected readonly tabFailed = signal(false);
  /** QA D1380: entries named by a later timeline page, for its labels (QA D829). */
  private readonly timelineRefs = signal<{
    encounters: ChartReviewEncounter[];
    notes: ChartReviewNote[];
    imaging: ChartReviewImaging[];
  }>({ encounters: [], notes: [], imaging: [] });

  /**
   * QA D1380 (D1347 class): each tab's count is the SERVER's total of the
   * section. The badges read CHRONOLOGIE 20, MÉDICAMENTS 20 for a patient with
   * 667 prescriptions: they counted the page on hand.
   */
  protected readonly counts = computed<Record<ChartReviewTab, number>>(() => {
    const totals = this.chart()?.totals;
    return {
      timeline: totals?.timeline ?? 0,
      encounters: totals?.encounters ?? 0,
      notes: totals?.notes ?? 0,
      results: totals?.results ?? 0,
      medications: totals?.medications ?? 0,
      imaging: totals?.imaging ?? 0,
      procedures: totals?.procedures ?? 0,
    };
  });

  /** QA D1380: how many rows the active tab's page holds (the last page is shorter). */
  protected rowsOnPage(tab: ChartReviewTab): number {
    return this.chart()?.[tab]?.length ?? 0;
  }

  /**
   * QA D829: the timeline's encounter and imaging rows carry the server's
   * title, which joins the raw codes ("OUTPATIENT — Médecine générale"). The
   * row is labelled from its own encounter or imaging entry instead, through
   * the same enum labels the Encounters and Imaging tabs use.
   */
  protected readonly encountersById = computed(() => {
    const byId = new Map<string, ChartReviewEncounter>();
    for (const e of this.timelineRefs().encounters) byId.set(e.id, e);
    for (const e of this.chart()?.encounters ?? []) byId.set(e.id, e);
    return byId;
  });

  /** QA D1076: a note's timeline row reads its template by name, never "SOAP — …" from the wire. */
  protected readonly notesById = computed(() => {
    const byId = new Map<string, ChartReviewNote>();
    for (const n of this.timelineRefs().notes) byId.set(n.id, n);
    for (const n of this.chart()?.notes ?? []) byId.set(n.id, n);
    return byId;
  });

  protected readonly imagingById = computed(() => {
    const byId = new Map<string, ChartReviewImaging>();
    for (const i of this.timelineRefs().imaging) byId.set(i.id, i);
    for (const i of this.chart()?.imaging ?? []) byId.set(i.id, i);
    return byId;
  });

  private readonly chartReviewService = inject(ChartReviewService);
  private readonly translate = inject(TranslateService);
  private readonly enumLabels = inject(EnumLabelService);
  private readonly destroyed$ = new Subject<void>();
  private inFlight?: Subscription;
  /** QA D1380: the in-flight read of a further tab page. */
  private pageInFlight?: Subscription;

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
    this.tabFailed.set(false);
  }

  /**
   * QA D1380: reads page `page` of the active tab only, and puts its rows in
   * place of the tab's current page. A newer request (or a patient change)
   * cancels an older one, so a slow page never paints over the wrong tab.
   */
  protected loadTabPage(page: number): void {
    const patientId = this.patientId();
    const current = this.chart();
    if (!patientId || !current) return;
    const tab = this.activeTab();
    this.pageInFlight?.unsubscribe();
    this.tabLoading.set(true);
    this.tabFailed.set(false);
    this.pageInFlight = this.chartReviewService
      .getChartReview(
        patientId,
        this.hospitalId() ?? undefined,
        current.limit,
        tab.toUpperCase() as ChartReviewSection,
        page,
      )
      .pipe(takeUntil(this.destroyed$))
      .subscribe({
        next: (c) => {
          const rows = (c?.[tab] ?? []) as TabRows[typeof tab];
          this.chart.update((prev) =>
            prev ? { ...prev, [tab]: rows, totals: c?.totals ?? prev.totals } : prev,
          );
          if (tab === 'timeline') {
            this.timelineRefs.set({
              encounters: c?.timelineRefs?.encounters ?? [],
              notes: c?.timelineRefs?.notes ?? [],
              imaging: c?.timelineRefs?.imaging ?? [],
            });
          }
          this.tabPage.update((pages) => ({ ...pages, [tab]: page }));
          this.tabLoading.set(false);
        },
        error: () => {
          this.tabLoading.set(false);
          this.tabFailed.set(true);
        },
      });
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
    // RESULT timeline events carry the abnormal flag in `status`, so the
    // pill must light up red for ABNORMAL/CRITICAL — otherwise abnormal
    // results would render with the same neutral chip as ROUTINE statuses.
    if (
      ABNORMAL_DANGER.has(upper) ||
      upper.includes('CANCEL') ||
      upper.includes('REJECT') ||
      upper.includes('FAIL')
    ) {
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
   * QA D897: the timeline pill reads as one phrase with the section pill
   * before it ("NOTE SIGNÉE", "CONSULTATION TERMINÉE"), so its status agrees
   * with that section's noun, whose gender differs by language (a French
   * procédure is feminine, a Spanish procedimiento masculine). Each section
   * whose noun needs it has its own labels under
   * CHART_REVIEW.TIMELINE_STATUS; any other value keeps the shared `status`
   * label.
   */
  protected timelineStatus(event: { section: string; status?: string | null }): string {
    const status = event.status;
    if (!status) return '';
    const key = `CHART_REVIEW.TIMELINE_STATUS.${event.section}.${status}`;
    const agreed: unknown = this.translate.instant(key);
    return typeof agreed === 'string' && agreed !== key
      ? agreed
      : this.enumLabels.transform(status, 'status');
  }

  /** Stable DOM id for a tab button — used to wire tabpanel `aria-labelledby`. */
  protected tabId(tab: ChartReviewTab): string {
    return `chart-review-tab-${tab}`;
  }

  /** Stable DOM id for a tabpanel — used to wire tab `aria-controls`. */
  protected panelId(tab: ChartReviewTab): string {
    return `chart-review-panel-${tab}`;
  }

  /**
   * Cancel any prior request before issuing the next one. This is the same
   * guard the Storyboard banner uses and exists for the same reason: a stale
   * response landing after a newer patient is selected would paint the wrong
   * patient's chart.
   */
  private load(patientId: string, hospitalId?: string, limit?: number): void {
    this.cancelInFlight();
    this.tabPage.set({ ...FIRST_PAGES });
    this.timelineRefs.set({ encounters: [], notes: [], imaging: [] });
    this.tabLoading.set(false);
    this.tabFailed.set(false);
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
    this.pageInFlight?.unsubscribe();
    this.pageInFlight = undefined;
  }
}
