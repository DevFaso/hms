import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  CashSession,
  CashSessionService,
  RemovedInvoiceLine,
} from '../../services/cash-session.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { isMobileMoney, mobileMoneyOperatorLabelKey } from '../../shared/mobile-money-operator';
import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../../shared/refusal-reason';
import { saveResponse } from '../../shared/download-file';
import { cashDay, cashLineKey } from './cash-session.component';
import { refuseFields } from '../../shared/forms/invalid-fields';

/** The note field a reopen without a reason marks. */
const NOTE_FIELD_ID = 'cash-supervisor-note';

/** A supervisor's action on one session, with its note. */
interface PendingAction {
  session: CashSession;
  mode: 'reopen' | 'review';
}

/**
 * Billing-money plan PR 10 (QA r65 D2326, D2672): « Caisses du jour », for the hospital admin and the accountant.
 * Every till of the hospital on a day (billing, reception, pharmacy), open ones flagged, each with what it expects,
 * what was counted and the difference; « Rouvrir » (with a reason) and « Marquer vérifiée », never on one's own
 * till; the day report (PDF) and the journal (CSV) of every movement.
 */
@Component({
  selector: 'app-cash-supervisor',
  standalone: true,
  imports: [
    FormControlStyleDirective,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    CfaPipe,
    ServerTimePipe,
    KeyLabelPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cash-supervisor.component.html',
  styleUrl: './cash-session.component.scss',
})
export class CashSupervisorComponent {
  private readonly api = inject(CashSessionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly maxDate = cashDay();
  readonly isMobileMoney = isMobileMoney;
  readonly operatorLabelKey = mobileMoneyOperatorLabelKey;
  readonly date = signal(cashDay());
  readonly sessions = signal<CashSession[]>([]);
  /** PR 10 review (QA D2703): the lines taken off draft invoices that day, each with its reason. */
  readonly removed = signal<RemovedInvoiceLine[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly expanded = signal<string | null>(null);
  readonly pending = signal<PendingAction | null>(null);
  readonly note = signal('');
  readonly noteRefused = signal(false);
  readonly busy = signal(false);
  private request = 0;

  constructor() {
    this.load();
  }

  pick(value: string): void {
    this.date.set(value || cashDay());
    this.load();
  }

  toggle(s: CashSession): void {
    this.expanded.set(this.expanded() === s.id ? null : s.id);
  }

  /** The latest close's counted amount of a line, or null while not closed. */
  countedOf(s: CashSession, line: { method: string; operator?: string | null }): number | null {
    const key = cashLineKey(line.method, line.operator);
    const count = s.counts.find(
      (c) => c.closeRound === s.closeRound && cashLineKey(c.method, c.operator) === key,
    );
    return count ? count.counted : null;
  }

  explanationsOf(s: CashSession): string[] {
    return s.counts
      .filter((c) => c.closeRound === s.closeRound && c.explanation)
      .map((c) => c.explanation as string);
  }

  ask(session: CashSession, mode: 'reopen' | 'review'): void {
    this.pending.set({ session, mode });
    this.note.set('');
    this.noteRefused.set(false);
  }

  cancelAsk(): void {
    this.pending.set(null);
  }

  submitAsk(): void {
    const action = this.pending();
    if (!action || this.busy()) return;
    const note = this.note().trim();
    if (action.mode === 'reopen' && !note) {
      this.noteRefused.set(true);
      refuseFields(
        this.host.nativeElement,
        [NOTE_FIELD_ID],
        [NOTE_FIELD_ID],
        this.translate.instant('BILLING.CASH_SESSION.REOPEN_REASON_REQUIRED'),
      );
      return;
    }
    refuseFields(this.host.nativeElement, [], [NOTE_FIELD_ID]);
    this.busy.set(true);
    const op =
      action.mode === 'reopen'
        ? this.api.reopen(action.session.id, note)
        : this.api.review(action.session.id, note);
    op.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.pending.set(null);
        this.sessions.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
        this.toast.success(
          this.translate.instant(
            action.mode === 'reopen'
              ? 'BILLING.CASH_SESSION.REOPENED_TOAST'
              : 'BILLING.CASH_SESSION.REVIEWED_TOAST',
          ),
        );
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('BILLING.CASH_SESSION.ACTION_FAILED'),
        );
      },
    });
  }

  dayReport(): void {
    this.api.dayReportPdf(this.date()).subscribe({
      next: (res) => saveResponse(res, `rapport-caisses-${this.date()}.pdf`),
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.PDF_FAILED')),
    });
  }

  journal(): void {
    this.api.journalCsv(this.date()).subscribe({
      next: (res) => saveResponse(res, `journal-caisses-${this.date()}.csv`),
      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('BILLING.CASH_SESSION.EXPORT_FAILED'),
        ),
    });
  }

  private load(): void {
    const seq = ++this.request;
    this.loading.set(true);
    this.failed.set(false);
    this.api
      .list(this.date())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (seq !== this.request) return;
          this.sessions.set(rows ?? []);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.request) return;
          this.sessions.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
    this.api
      .removedLines(this.date())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (seq === this.request) this.removed.set(rows ?? []);
        },
        error: () => {
          if (seq === this.request) this.removed.set([]);
        },
      });
  }
}
