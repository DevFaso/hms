import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  CashCount,
  CashMethodLine,
  CashSession,
  CashSessionService,
  CashTill,
} from '../../services/cash-session.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { isMobileMoney, mobileMoneyOperatorLabelKey } from '../../shared/mobile-money-operator';
import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../../shared/refusal-reason';
import { saveResponse } from '../../shared/download-file';
import { FieldRefusal, refuseFields } from '../../shared/forms/invalid-fields';

/** Today in the browser's calendar, as YYYY-MM-DD. */
export function cashDay(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** The key of a method line: the method, and the operator for mobile money. */
export function cashLineKey(method: string, operator?: string | null): string {
  return `${method}|${method === 'MOBILE_MONEY' ? (operator ?? '') : ''}`;
}

/** What the cashier typed for one method. */
interface Typed {
  counted: number | null;
  explanation: string;
}

/**
 * Billing-money plan PR 10 (QA r65 D2325, D2714, D2672): « Ma caisse du jour ». Each till the signed-in user took money
 * at today (billing, reception, pharmacy): per method the money in, the money out and what is expected; the cash to
 * count on its own (never mixed with card or mobile money); the counted amounts, the difference and its explanation;
 * then « Clôturer la caisse ». A closed till shows its counts and takes no new payment until a manager reopens it.
 */
@Component({
  selector: 'app-cash-session',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, CfaPipe, ServerTimePipe, KeyLabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cash-session.component.html',
  styleUrl: './cash-session.component.scss',
})
export class CashSessionComponent {
  private readonly api = inject(CashSessionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Only this till (the pharmacy page shows the PHARMACY till); every till when empty. */
  readonly till = input<CashTill | null>(null);

  readonly maxDate = cashDay();
  readonly isMobileMoney = isMobileMoney;
  readonly operatorLabelKey = mobileMoneyOperatorLabelKey;
  readonly date = signal(cashDay());
  readonly all = signal<CashSession[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly busy = signal<string | null>(null);
  /** What was typed per session and method line. */
  readonly typed = signal<Record<string, Typed>>({});
  /** Per session, the refusal shown under its form (a missing count or explanation). */
  readonly refusals = signal<Record<string, string>>({});

  readonly sessions = computed(() => {
    const only = this.till();
    return this.all().filter((s) => !only || s.till === only);
  });

  private request = 0;

  constructor() {
    this.load();
  }

  pick(value: string): void {
    this.date.set(value || cashDay());
    this.load();
  }

  /** Only the session's cashier closes it, while it is open. */
  canClose(s: CashSession): boolean {
    return s.mine && s.status === 'OPEN';
  }

  private typedKey(s: CashSession, line: { method: string; operator?: string | null }): string {
    return `${s.id}|${cashLineKey(line.method, line.operator)}`;
  }

  countedOf(s: CashSession, line: CashMethodLine): number | null {
    return this.typed()[this.typedKey(s, line)]?.counted ?? null;
  }

  explanationOf(s: CashSession, line: CashMethodLine): string {
    return this.typed()[this.typedKey(s, line)]?.explanation ?? '';
  }

  setCounted(s: CashSession, line: CashMethodLine, value: number | string | null): void {
    const counted = value === null || value === '' ? null : Number(value);
    const key = this.typedKey(s, line);
    this.typed.update((all) => ({
      ...all,
      [key]: { explanation: all[key]?.explanation ?? '', counted },
    }));
  }

  setExplanation(s: CashSession, line: CashMethodLine, value: string): void {
    const key = this.typedKey(s, line);
    this.typed.update((all) => ({
      ...all,
      [key]: { counted: all[key]?.counted ?? null, explanation: value },
    }));
  }

  /** The difference the typed amount makes (null while nothing is typed). */
  differenceOf(s: CashSession, line: CashMethodLine): number | null {
    const counted = this.countedOf(s, line);
    return counted === null || Number.isNaN(counted) ? null : counted - Number(line.expected ?? 0);
  }

  /** The latest close's count of a line (closed or reviewed sessions). */
  lastCount(s: CashSession, line: CashMethodLine): CashCount | null {
    return (
      s.counts.find(
        (c) =>
          c.closeRound === s.closeRound &&
          cashLineKey(c.method, c.operator) === cashLineKey(line.method, line.operator),
      ) ?? null
    );
  }

  /** Counts of the closes before the latest one (a reopened session closed again). */
  earlierCounts(s: CashSession): CashCount[] {
    return s.counts.filter((c) => c.closeRound < s.closeRound);
  }

  /** The id of a line's counted-amount input (a refusal marks and focuses it). */
  countId(s: CashSession, line: { method: string; operator?: string | null }): string {
    return `cash-count-${s.id}-${line.method}-${line.operator ?? ''}`;
  }

  /** The id of a line's explanation input. */
  explanationId(s: CashSession, line: { method: string; operator?: string | null }): string {
    return `cash-explanation-${s.id}-${line.method}-${line.operator ?? ''}`;
  }

  methodKey(line: { method?: string | null }): string {
    return line.method ?? '';
  }

  close(s: CashSession): void {
    if (this.busy()) return;
    const problems = this.closeProblems(s);
    const allIds = s.methods.flatMap((line) => [
      this.countId(s, line),
      this.explanationId(s, line),
    ]);
    // Every refused count or explanation is marked at once, each under its own reason, the first focused.
    refuseFields(this.host.nativeElement, problems, allIds);
    this.refusals.update((all) => ({ ...all, [s.id]: problems[0]?.message ?? '' }));
    if (problems.length > 0) {
      return;
    }
    this.busy.set(s.id);
    const counts = s.methods.map((line) => ({
      method: line.method,
      operator: line.operator ?? null,
      counted: this.countedOf(s, line),
      explanation: this.explanationOf(s, line).trim() || undefined,
    }));
    this.api
      .close(s.id, counts)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (closed) => {
          this.busy.set(null);
          this.replace(closed);
          this.toast.success(this.translate.instant('BILLING.CASH_SESSION.CLOSED_TOAST'));
        },
        error: (err: unknown) => {
          this.busy.set(null);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('BILLING.CASH_SESSION.CLOSE_FAILED'),
          );
          this.load();
        },
      });
  }

  /** Every method counted, in whole francs; a difference explained. Empty: it may be sent. */
  private closeProblems(s: CashSession): FieldRefusal[] {
    const refusals: FieldRefusal[] = [];
    for (const line of s.methods) {
      const counted = this.countedOf(s, line);
      const method = this.lineLabel(line);
      if (counted === null || Number.isNaN(counted)) {
        refusals.push({
          id: this.countId(s, line),
          message: this.translate.instant('BILLING.CASH_SESSION.COUNT_REQUIRED', { method }),
        });
      } else if (counted < 0 || !Number.isInteger(counted)) {
        refusals.push({
          id: this.countId(s, line),
          message: this.translate.instant('BILLING.CASH_SESSION.COUNT_WHOLE', { method }),
        });
      } else if (this.differenceOf(s, line) !== 0 && !this.explanationOf(s, line).trim()) {
        refusals.push({
          id: this.explanationId(s, line),
          message: this.translate.instant('BILLING.CASH_SESSION.EXPLANATION_REQUIRED', { method }),
        });
      }
    }
    return refusals;
  }

  private lineLabel(line: { method: string; operator?: string | null }): string {
    if (isMobileMoney(line.method)) {
      return this.translate.instant(mobileMoneyOperatorLabelKey(line.operator ?? null));
    }
    return this.translate.instant(`BILLING.PAYMENT_METHODS.${line.method}`);
  }

  printSheet(s: CashSession): void {
    this.api.sessionPdf(s.id).subscribe({
      next: (res) => saveResponse(res, `feuille-de-caisse-${s.businessDate}.pdf`),
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('BILLING.PDF_FAILED')),
    });
  }

  private replace(updated: CashSession): void {
    this.all.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
  }

  private load(): void {
    const seq = ++this.request;
    this.loading.set(true);
    this.failed.set(false);
    this.api
      .mine(this.date())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (seq !== this.request) return;
          this.all.set(rows ?? []);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.request) return;
          this.all.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }
}
