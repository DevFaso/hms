import {
  Component,
  ElementRef,
  computed,
  OnInit,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { LabCorrectionBadgeComponent } from '../../lab/shared/lab-correction-badge.component';
import { PatientPortalService, LabResultSummary } from '../../services/patient-portal.service';
import { LabResultPager } from '../lab-result-pager';
import { focusWhenRendered } from '../../shared/a11y/focus-when-rendered';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { localizeDecimals } from '../../shared/i18n/app-locale';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

@Component({
  selector: 'app-my-lab-results',
  standalone: true,
  imports: [
    LabCorrectionBadgeComponent,
    ServerTimePipe,
    CommonModule,
    DatePipe,
    EnumLabelPipe,
    TranslateModule,
  ],
  templateUrl: './my-lab-results.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-lab-results.component.scss', '../patient-portal-pages.scss'],
})
export class MyLabResultsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** QA D1213: every current result, page by page (see LabResultPager). */
  readonly pager = new LabResultPager((offset) => this.portal.getMyLabResultsPage(offset));
  readonly results = this.pager.results;
  /** The server's reason the last read failed (a 4xx), when it gave one. */
  readonly failureReason = this.pager.failureReason;
  /** The first read only: a later page keeps the list on screen. */
  readonly loading = computed(() => this.pager.loading() && this.results().length === 0);
  /** The first read failed: the page says so (and why), never "no results". */
  readonly firstReadFailed = computed(() => this.pager.loadFailed() && this.results().length === 0);
  expandedId = signal<string | null>(null);

  ngOnInit() {
    this.pager.reload();
  }

  /** Reads the first page again after it failed. */
  retry(): void {
    this.pager.reload();
  }

  /**
   * Shows the older results. When none are left the button goes, so the
   * keyboard moves to the first result just added rather than the page body.
   */
  loadMore(): void {
    this.pager.loadMore((fresh) => {
      if (!this.pager.hasMore() && fresh.length > 0) {
        focusWhenRendered(this.host.nativeElement, `[data-lab-id="${CSS.escape(fresh[0].id)}"]`);
      }
    });
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }

  /**
   * Three states, not two: a result the lab has not released yet carries no
   * value at all, so drawing it with the "all clear" check beside an empty
   * value told the patient their test was normal before anyone had read it.
   */
  labIcon(lab: LabResultSummary): string {
    if (lab.isPending) return 'hourglass_top';
    if (lab.isUngraded) return 'info';
    return lab.isAbnormal ? 'warning' : 'check_circle';
  }

  labIconBackground(lab: LabResultSummary): string {
    if (lab.isPending) return '#fef3c7';
    if (lab.isUngraded) return '#eef2f7';
    return lab.isAbnormal ? '#fee2e2' : '#d1fae5';
  }

  labIconColor(lab: LabResultSummary): string {
    if (lab.isPending) return '#b45309';
    if (lab.isUngraded) return '#33415c';
    return lab.isAbnormal ? '#dc2626' : '#059669';
  }

  interpretation(lab: LabResultSummary): string {
    if (lab.isPending) return 'PORTAL.LAB_RESULTS.PENDING_INTERPRETATION';
    // Not compared with a range in its unit: "within normal limits" would be a claim nobody made.
    if (lab.isUngraded) return 'PORTAL.LAB_RESULTS.NOT_GRADED_RESULT';
    return lab.isAbnormal
      ? 'PORTAL.LAB_RESULTS.ABNORMAL_RESULT'
      : 'PORTAL.LAB_RESULTS.NORMAL_RESULT';
  }

  /** QA D344: a numeric result in the patient's notation ("7,9" in French). */
  displayResult(result: string | null | undefined): string {
    return localizeDecimals(result ?? '');
  }
}
