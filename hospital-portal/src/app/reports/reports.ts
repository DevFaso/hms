import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  ReportDefinitionResponse,
  ReportPeriod,
  ReportRunResponse,
  ReportType,
  ReportsService,
} from './reports.service';
import { ToastService } from '../core/toast.service';

/**
 * Scheduled reports (P3 #25a): the admin page for aggregate-only CSV
 * reports emailed each closed period. First caller of every /reports
 * endpoint. Content is counts per day — never patient rows — because
 * email must not carry PHI.
 */
@Component({
  selector: 'app-reports',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './reports.html',
  styleUrl: './reports.scss',
})
export class ReportsComponent implements OnInit {
  private readonly reportsService = inject(ReportsService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── List state ─────────────── */
  definitions = signal<ReportDefinitionResponse[]>([]);
  loading = signal(false);
  actingOnId = signal<string | null>(null);

  /* ── Run history ────────────── */
  expandedId = signal<string | null>(null);
  runs = signal<ReportRunResponse[]>([]);
  runsLoading = signal(false);

  /* ── Create modal ───────────── */
  showCreate = signal(false);
  saving = signal(false);
  name = signal('');
  reportType = signal<ReportType>('ENCOUNTER_ACTIVITY');
  period = signal<ReportPeriod>('MONTHLY');
  recipients = signal('');

  readonly reportTypes: ReportType[] = [
    'ENCOUNTER_ACTIVITY',
    'APPOINTMENT_ACTIVITY',
    'TOP_DIAGNOSES',
  ];
  readonly periods: ReportPeriod[] = ['DAILY', 'WEEKLY', 'MONTHLY'];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.reportsService.list().subscribe({
      next: (list) => {
        this.definitions.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('REPORTS.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.name.set('');
    this.reportType.set('ENCOUNTER_ACTIVITY');
    this.period.set('MONTHLY');
    this.recipients.set('');
    this.showCreate.set(true);
  }

  submitCreate(): void {
    if (!this.name().trim() || !this.recipients().trim()) {
      this.toast.error(this.translate.instant('REPORTS.FIELDS_REQUIRED'));
      return;
    }
    if (this.saving()) return;
    this.saving.set(true);
    this.reportsService
      .create({
        name: this.name().trim(),
        reportType: this.reportType(),
        period: this.period(),
        recipients: this.recipients().trim(),
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('REPORTS.CREATED'));
          this.showCreate.set(false);
          this.saving.set(false);
          this.load();
        },
        error: (err) => {
          this.toast.error(err?.error?.message ?? this.translate.instant('REPORTS.CREATE_FAILED'));
          this.saving.set(false);
        },
      });
  }

  toggleRuns(definition: ReportDefinitionResponse): void {
    if (this.expandedId() === definition.id) {
      this.expandedId.set(null);
      return;
    }
    this.expandedId.set(definition.id);
    this.runs.set([]);
    this.runsLoading.set(true);
    this.reportsService.runs(definition.id).subscribe({
      next: (list) => {
        this.runs.set(list);
        this.runsLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('REPORTS.RUNS_LOAD_FAILED'));
        this.runsLoading.set(false);
      },
    });
  }

  runNow(definition: ReportDefinitionResponse): void {
    if (this.actingOnId()) return;
    this.actingOnId.set(definition.id);
    this.reportsService.runNow(definition.id).subscribe({
      next: (run) => {
        this.actingOnId.set(null);
        if (run.status === 'SUCCEEDED') {
          this.toast.success(this.translate.instant('REPORTS.RUN_SENT'));
        } else {
          this.toast.error(run.errorMessage ?? this.translate.instant('REPORTS.RUN_NOW_FAILED'));
        }
        if (this.expandedId() === definition.id) this.toggleRuns(definition);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('REPORTS.RUN_NOW_FAILED'));
        this.actingOnId.set(null);
      },
    });
  }

  toggleActive(definition: ReportDefinitionResponse): void {
    if (this.actingOnId()) return;
    this.actingOnId.set(definition.id);
    const call = definition.active
      ? this.reportsService.deactivate(definition.id)
      : this.reportsService.reactivate(definition.id);
    call.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(definition.active ? 'REPORTS.DEACTIVATED' : 'REPORTS.REACTIVATED'),
        );
        this.actingOnId.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('REPORTS.ACTION_FAILED'));
        this.actingOnId.set(null);
      },
    });
  }

  statusClass(status: string): string {
    return 'run-status-' + status.toLowerCase();
  }
}
