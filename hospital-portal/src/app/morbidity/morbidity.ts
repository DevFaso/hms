import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { MorbidityDashboard, MorbidityService } from './morbidity.service';
import { DiagnosisBarsComponent } from './diagnosis-bars/diagnosis-bars.component';
import { ToastService } from '../core/toast.service';

/**
 * Disease surveillance: the diagnoses recorded most in a given month.
 *
 * <p>Scope comes from the BACKEND, not from anything this page sends —
 * there is no hospital selector, because the server decides from the
 * caller's own authorities. A hospital admin gets their own facility;
 * an unscoped super-admin additionally gets `byHospital`, which is what
 * answers "malaria is highest at A, cholera at B".
 *
 * <p>The page renders whichever it receives rather than branching on the
 * user's role locally: the server is the authority on scope, and mirroring
 * that decision in the client would be a second place for it to drift.
 */
@Component({
  selector: 'app-morbidity',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, DiagnosisBarsComponent],
  templateUrl: './morbidity.html',
  styleUrl: './morbidity.scss',
})
export class MorbidityComponent implements OnInit {
  private readonly morbidityService = inject(MorbidityService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** Oldest month the backend will serve (it rejects beyond 36 months). */
  private static readonly MAX_MONTHS_BACK = 36;

  data = signal<MorbidityDashboard | null>(null);
  loading = signal(false);
  failed = signal(false);
  /** Selected month as yyyy-MM — the same shape the API takes. */
  month = signal<string>(MorbidityComponent.currentMonth());

  readonly isNetwork = computed(() => this.data()?.scope === 'NETWORK');
  readonly hasBreakdown = computed(() => (this.data()?.byHospital?.length ?? 0) > 0);

  /** Disable "next" once the selected month reaches the current one. */
  readonly atLatestMonth = computed(() => this.month() >= MorbidityComponent.currentMonth());
  readonly atEarliestMonth = computed(() => this.month() <= MorbidityComponent.earliestMonth());

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.morbidityService.topDiagnoses(this.month()).subscribe({
      next: (dashboard) => {
        this.data.set(dashboard);
        this.loading.set(false);
      },
      error: () => {
        // Explicit error state rather than an empty chart: "no diagnoses
        // this month" and "the request failed" must not look alike.
        this.data.set(null);
        this.failed.set(true);
        this.loading.set(false);
        this.toast.error(this.translate.instant('MORBIDITY.LOAD_FAILED'));
      },
    });
  }

  previousMonth(): void {
    if (this.atEarliestMonth()) return;
    this.month.set(MorbidityComponent.shift(this.month(), -1));
    this.load();
  }

  nextMonth(): void {
    if (this.atLatestMonth()) return;
    this.month.set(MorbidityComponent.shift(this.month(), 1));
    this.load();
  }

  /** Grand total across the ranked rows, for the summary line. */
  totalShown(): number {
    return (this.data()?.overall ?? []).reduce((sum, slice) => sum + slice.count, 0);
  }

  /** The single diagnosis leading a hospital, for the card subtitle. */
  leadDiagnosis(index: number): string {
    const top = this.data()?.byHospital?.[index]?.top ?? [];
    return top.length > 0 ? top[0].display : '';
  }

  /* ── month arithmetic (yyyy-MM strings, no Date parsing) ────────── */

  private static currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private static earliestMonth(): string {
    return MorbidityComponent.shift(
      MorbidityComponent.currentMonth(),
      -MorbidityComponent.MAX_MONTHS_BACK,
    );
  }

  /** Shift a yyyy-MM string by N months, rolling the year correctly. */
  private static shift(month: string, delta: number): string {
    const [year, mon] = month.split('-').map(Number);
    // Work in absolute months so negative deltas roll the year back
    // without the modulo going negative.
    const absolute = year * 12 + (mon - 1) + delta;
    const newYear = Math.floor(absolute / 12);
    const newMonth = absolute - newYear * 12 + 1;
    return `${newYear}-${String(newMonth).padStart(2, '0')}`;
  }
}
