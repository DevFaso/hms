import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  BillingInvoiceResponse,
  BillingService,
  InvoicePayerShare,
  PayerShareDecision,
} from '../../services/billing.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { containsCardNumber } from '../../shared/mobile-money-operator';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { markServerFieldErrors } from '../../shared/forms/server-field-errors';

/** The rejection reason's field id (a refusal marks it). */
export const PAYER_SHARE_REASON_ID = 'payer-share-reject-reason';

/**
 * Billing-money plan PR 7: the payer's coverage on one invoice, on its detail panel.
 *
 * <p>"Part assureur / Part patient" come with every invoice. The shares themselves (payer, plan, amount, status and,
 * for a rejected one, the payer's reason "Refusé par l'assureur : motif") are listed for the roles that read them
 * (InvoicePayerShareController); the hospital admin, the accountant, the billing specialist and the claims reviewer
 * record the payer's decision: "Marquer réclamée", "Marquer payée", "Refuser" (with the payer's reason, never a card
 * number). A rejection moves the share back to the patient, so the parent re-reads the invoice.
 */
@Component({
  selector: 'app-invoice-payer-shares',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, CfaPipe, KeyLabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoice-payer-shares.component.html',
  styleUrl: './invoice-payer-shares.component.scss',
})
export class InvoicePayerSharesComponent {
  private readonly billing = inject(BillingService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** The invoice whose payer shares are shown. */
  readonly invoice = input.required<BillingInvoiceResponse>();
  /** A decision moved what the patient owes: the parent re-reads the invoice. */
  readonly changed = output<void>();

  /** The server's roles (InvoicePayerShareController); the server checks the assignment again. */
  readonly canRead = this.roleContext.hasAnyActiveRole([
    'ROLE_SUPER_ADMIN',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_CLAIMS_REVIEWER',
  ]);
  readonly canDecide = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_CLAIMS_REVIEWER',
  ]);

  readonly shares = signal<InvoicePayerShare[]>([]);
  readonly loadFailed = signal(false);
  readonly busy = signal(false);
  /** The share being refused, while its reason is typed. */
  readonly rejecting = signal<InvoicePayerShare | null>(null);
  readonly rejectReason = signal('');

  readonly payerShare = computed(() => Number(this.invoice().insuranceCoverageAmount ?? 0));
  readonly patientShare = computed(() => Number(this.invoice().patientResponsibilityAmount ?? 0));
  /** Shown when a payer covers part of the bill now, or ever did (a rejected or cancelled share). */
  readonly visible = computed(() => this.payerShare() > 0 || this.shares().length > 0);

  constructor() {
    effect(() => {
      const id = this.invoice().id;
      untracked(() => this.load(id));
    });
  }

  private load(invoiceId: string): void {
    this.rejecting.set(null);
    if (!this.canRead || !invoiceId) {
      this.shares.set([]);
      return;
    }
    this.billing
      .listPayerShares(invoiceId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.shares.set(rows ?? []);
          this.loadFailed.set(false);
        },
        error: () => this.loadFailed.set(true),
      });
  }

  /** "Marquer réclamée" on an open share. */
  canClaim(share: InvoicePayerShare): boolean {
    return this.canDecide && share.status === 'OPEN';
  }

  /** "Marquer payée" and "Refuser" on an open or claimed share. */
  canSettle(share: InvoicePayerShare): boolean {
    return this.canDecide && (share.status === 'OPEN' || share.status === 'CLAIMED');
  }

  decide(share: InvoicePayerShare, decision: PayerShareDecision, reason?: string): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.billing
      .decidePayerShare(this.invoice().id, share.id, decision, reason)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.busy.set(false);
          this.shares.set(rows ?? []);
          this.rejecting.set(null);
          this.toast.success(this.translate.instant('BILLING.PAYER_SHARES.DECIDED_TOAST'));
          this.changed.emit();
        },
        error: (err: unknown) => {
          this.busy.set(false);
          // The server names the reason when it refuses it (blank, too long, a card number).
          const marked =
            decision === 'reject'
              ? markServerFieldErrors(this.host.nativeElement, err, {
                  reason: PAYER_SHARE_REASON_ID,
                })
              : [];
          if (marked.length === 0) {
            this.toast.error(
              refusalReason(err) ?? this.translate.instant('BILLING.PAYER_SHARES.FAILED'),
            );
          }
        },
      });
  }

  openReject(share: InvoicePayerShare): void {
    this.rejecting.set(share);
    this.rejectReason.set('');
  }

  closeReject(): void {
    this.rejecting.set(null);
  }

  /** The payer's reason is required, and never a card number (D-15); the server checks both again. */
  confirmReject(): void {
    const share = this.rejecting();
    if (!share) return;
    const reason = this.rejectReason().trim();
    const refusal = !reason
      ? 'BILLING.PAYER_SHARES.REASON_REQUIRED'
      : containsCardNumber(reason)
        ? 'BILLING.PAYER_SHARES.NO_CARD_NUMBER'
        : null;
    if (refusal) {
      refuseFields(
        this.host.nativeElement,
        [PAYER_SHARE_REASON_ID],
        [PAYER_SHARE_REASON_ID],
        this.translate.instant(refusal),
      );
      return;
    }
    this.decide(share, 'reject', reason);
  }
}
