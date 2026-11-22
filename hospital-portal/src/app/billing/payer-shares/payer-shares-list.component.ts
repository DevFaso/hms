import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  BillingService,
  OpenPayerShare,
  PayerShareDecision,
  PayerShareStatus,
} from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { formatCfa } from '../../shared/i18n/app-locale';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { containsCardNumber } from '../../shared/mobile-money-operator';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';

/** The field id of a share's rejection reason. */
export function payerShareReasonId(share: { id: string }): string {
  return `payer-share-list-reason-${share.id}`;
}

/**
 * QA D2706 (r70): « Parts assureurs », the hospital's insurer shares in one list. The claims reviewer had no screen
 * for them (403 on /billing; the share was reachable only through the invoice drawer). OPEN and CLAIMED shares by
 * default, the paid and refused ones on demand; the roles that decide a share (InvoicePayerShareController) mark it
 * claimed or paid, or record the payer's refusal with its reason (never a card number). The server checks every
 * step again.
 */
@Component({
  selector: 'app-payer-shares-list',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, CfaPipe, DayMonthPipe, KeyLabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './payer-shares-list.component.html',
  styleUrl: './payer-shares-list.component.scss',
})
export class PayerSharesListComponent {
  private readonly billing = inject(BillingService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly confirm = inject(ConfirmService);

  /** The roles that decide a share (the super-admin reads only). */
  readonly canDecide = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_CLAIMS_REVIEWER',
  ]);

  readonly view = signal<'open' | 'closed'>('open');
  readonly rows = signal<OpenPayerShare[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly busy = signal<string | null>(null);
  readonly rejecting = signal<string | null>(null);
  readonly rejectReason = signal('');
  readonly total = computed(() => this.rows().reduce((sum, row) => sum + (row.amount ?? 0), 0));

  private request = 0;

  constructor() {
    this.load();
  }

  setView(view: 'open' | 'closed'): void {
    if (this.view() === view) return;
    this.view.set(view);
    this.load();
  }

  load(): void {
    const seq = ++this.request;
    const statuses: PayerShareStatus[] =
      this.view() === 'open' ? ['OPEN', 'CLAIMED'] : ['PAID', 'REJECTED'];
    this.loading.set(true);
    this.failed.set(false);
    this.billing
      .listHospitalPayerShares(statuses)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (seq !== this.request) return;
          this.rows.set(rows ?? []);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.request) return;
          this.rows.set([]);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  canClaim(row: OpenPayerShare): boolean {
    return this.canDecide && row.status === 'OPEN';
  }

  canSettle(row: OpenPayerShare): boolean {
    return this.canDecide && (row.status === 'OPEN' || row.status === 'CLAIMED');
  }

  startReject(row: OpenPayerShare): void {
    this.rejecting.set(row.id);
    this.rejectReason.set('');
  }

  stopReject(): void {
    this.rejecting.set(null);
  }

  submitReject(row: OpenPayerShare): void {
    const reason = this.rejectReason().trim();
    const id = payerShareReasonId(row);
    let problem: string | null = null;
    if (!reason) {
      problem = this.translate.instant('R70_MONEY.SHARES.REASON_REQUIRED');
    } else if (containsCardNumber(reason)) {
      problem = this.translate.instant('MOBILE_MONEY.CARD_NUMBER_REFUSED');
    }
    if (problem) {
      refuseFields(this.host.nativeElement, [id], [id], problem);
      this.toast.error(problem);
      return;
    }
    this.decide(row, 'reject', reason);
  }

  /** Review of #1284: « Marquer payée » settles the share for good; it is confirmed first, naming the amount. */
  async markPaid(row: OpenPayerShare): Promise<void> {
    const sure = await this.confirm.ask({
      titleKey: 'R70_MONEY.SHARES.PAID_CONFIRM',
      params: { amount: formatCfa(row.amount, 'XOF'), payer: row.payerName ?? '' },
      confirmKey: 'R70_MONEY.SHARES.PAID',
    });
    if (sure) this.decide(row, 'paid');
  }

  decide(row: OpenPayerShare, decision: PayerShareDecision, reason?: string): void {
    if (this.busy()) return;
    this.busy.set(row.id);
    this.billing
      .decidePayerShare(row.invoiceId, row.id, decision, reason)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(null);
          this.rejecting.set(null);
          this.toast.success(
            this.translate.instant('R70_MONEY.SHARES.DONE.' + decision.toUpperCase()),
          );
          this.load();
        },
        error: (err: unknown) => {
          this.busy.set(null);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('R70_MONEY.SHARES.DECIDE_FAILED'),
          );
        },
      });
  }

  reasonId(row: OpenPayerShare): string {
    return payerShareReasonId(row);
  }
}
