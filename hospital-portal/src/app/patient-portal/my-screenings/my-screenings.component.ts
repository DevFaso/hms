import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  ProAnswers,
  ProInstrumentView,
  ProScreeningService,
  ProSelfReportAvailable,
  ProSelfReportEntry,
} from '../../services/pro-screening.service';
import { ToastService } from '../../core/toast.service';
import {
  ProInstrumentFormComponent,
  unansweredItems,
} from '../../shared/pro-instrument-form/pro-instrument-form.component';

/**
 * A mother answers her own mental-health screening (EPDS) from home while a
 * postpartum plan is open for her (Tier 2 item 47).
 *
 * Deliberately score-free. The backend never sends a total to this surface
 * and this page never computes one: a number without a conversation is not
 * something to hand a new mother at 3 a.m. What she sees afterwards is
 * whether her care team will follow up, and whether they were alerted
 * straight away.
 */
@Component({
  selector: 'app-my-screenings',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule, TranslateModule, ProInstrumentFormComponent],
  templateUrl: './my-screenings.component.html',
  styleUrls: ['./my-screenings.component.scss', '../patient-portal-pages.scss'],
})
export class MyScreeningsComponent implements OnInit {
  private readonly screenings = inject(ProScreeningService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  loading = signal(true);
  /**
   * Distinguished from "nothing available" on purpose: an empty list reads
   * as "no screening is open for you", which is the wrong thing to tell a
   * patient when the request simply failed.
   */
  failed = signal(false);
  available = signal<ProSelfReportAvailable[]>([]);
  history = signal<ProSelfReportEntry[]>([]);

  /* ── Answering ── */
  active = signal<ProSelfReportAvailable | null>(null);
  instrument = signal<ProInstrumentView | null>(null);
  instrumentLoading = signal(false);
  instrumentFailed = signal(false);
  answers = signal<ProAnswers>({});
  submitting = signal(false);
  language = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.screenings.myScreenings().subscribe({
      next: (report) => {
        this.available.set(report.available ?? []);
        this.history.set(report.history ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  start(instrument: ProSelfReportAvailable): void {
    this.active.set(instrument);
    this.answers.set({});
    this.language = this.translate.currentLang || '';
    this.loadInstrument(instrument.code, this.language);
  }

  cancel(): void {
    this.active.set(null);
    this.instrument.set(null);
  }

  changeLanguage(language: string): void {
    const active = this.active();
    if (!active) return;
    this.language = language;
    this.loadInstrument(active.code, language);
  }

  private loadInstrument(code: string, language: string): void {
    this.instrumentLoading.set(true);
    this.instrumentFailed.set(false);
    this.screenings.myInstrument(code, language || undefined).subscribe({
      next: (view) => {
        this.instrument.set(view);
        this.language = view.language;
        this.instrumentLoading.set(false);
      },
      error: () => {
        this.instrument.set(null);
        this.instrumentFailed.set(true);
        this.instrumentLoading.set(false);
      },
    });
  }

  unanswered(): number[] {
    const instrument = this.instrument();
    return instrument ? unansweredItems(instrument, this.answers()) : [];
  }

  submit(): void {
    const instrument = this.instrument();
    if (!instrument) return;
    const missing = this.unanswered();
    if (missing.length > 0) {
      this.toast.warning(this.translate.instant('PRO.INCOMPLETE', { items: missing.join(', ') }));
      return;
    }
    this.submitting.set(true);
    this.screenings
      .submitMine({
        instrumentCode: instrument.code,
        language: instrument.language,
        answers: this.answers(),
      })
      .subscribe({
        next: (entry) => {
          this.submitting.set(false);
          this.history.update((list) => [entry, ...list]);
          this.cancel();
          this.toast.success(this.translate.instant('PRO.MY.SUBMITTED'));
        },
        error: () => {
          this.toast.error(this.translate.instant('PRO.MY.SUBMIT_ERROR'));
          this.submitting.set(false);
        },
      });
  }
}
