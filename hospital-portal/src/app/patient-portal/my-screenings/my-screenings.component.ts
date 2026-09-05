import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, catchError, of, switchMap, tap } from 'rxjs';

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

  /**
   * One pipeline for the instrument, keyed on (code, language): a language
   * switch cancels the request before it, so a slow first answer can never
   * overwrite the wording the mother chose. Cancelling clears the key so a
   * late response for a closed form is dropped too.
   */
  private readonly instrumentRequests = new Subject<{ code: string; language: string } | null>();

  constructor() {
    this.instrumentRequests
      .pipe(
        tap((request) => {
          this.instrumentLoading.set(request !== null);
          this.instrumentFailed.set(false);
        }),
        switchMap((request) =>
          request
            ? this.screenings
                .myInstrument(request.code, request.language || undefined)
                .pipe(catchError(() => of('failed' as const)))
            : of(null),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((view) => {
        this.instrumentLoading.set(false);
        if (view === 'failed') {
          this.instrument.set(null);
          this.instrumentFailed.set(true);
          return;
        }
        this.instrument.set(view);
        if (view) this.language = view.language;
      });
  }

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
    this.instrumentRequests.next({ code: instrument.code, language: this.language });
  }

  cancel(): void {
    this.active.set(null);
    this.instrumentRequests.next(null);
  }

  changeLanguage(language: string): void {
    const active = this.active();
    if (!active) return;
    this.language = language;
    this.instrumentRequests.next({ code: active.code, language });
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
