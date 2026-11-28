import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
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
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  BillingService,
  OpenPayerShare,
  PayerShareDecision,
  PayerShareStatus,
} from '../../services/billing.service';
import { Payer, PayerCatalogueService, PayerPlan } from '../../services/payer-catalogue.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { containsCardNumber } from '../../shared/mobile-money-operator';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { ClaimsNavComponent } from '../insurer-claims/claims-nav.component';

/** The field id of a share's rejection reason. */
export function payerShareReasonId(share: { id: string }): string {
  return `payer-share-list-reason-${share.id}`;
}

/**
 * QA D2706 (r70): « Parts assureurs », the hospital's insurer shares in one list. The claims reviewer had no screen
 * for them (403 on /billing; the share was reachable only through the invoice drawer). The shares still owed by
 * default, the closed ones on demand.
 *
 * Insurer claims PRs 2 and 3 (QA D3983): a share is claimed by sending its bordereau and paid by an insurer payment
 * with an amount, a date and a reference, so « Marquer réclamée » and « Marquer payée » are gone. Each row shows the
 * bordereau it is on, what was paid, refused and written off, the payer's reason and the date of the last change.
 * The only step left here is the payer's refusal of a share not yet claimed (the cover was wrong).
 *
 * Insurer claims PR 1 (G1, G2): filtered by payer, plan and service period, paged by the server, with the total of
 * every page; each share shows the policy number, the subscriber and the acts claimed, each with its act code and
 * pre-authorisation number (QA D3984).
 */
@Component({
  selector: 'app-payer-shares-list',
  standalone: true,
  imports: [
    FormControlStyleDirective,
    CommonModule,
    FormsModule,
    TranslateModule,
    CfaPipe,
    DayMonthPipe,
    KeyLabelPipe,
    EnumLabelPipe,
    RouterLink,
    ClaimsNavComponent,
  ],
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

  /**
   * Review of #1394 (F2): who refuses a whole share, which the patient then owes: the hospital admin or the
   * accountant (the server answers the same).
   */
  readonly canDecide = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
  ]);

  /** The page size the list asks for. */
  static readonly PAGE_SIZE = 25;

  private readonly payerCatalogue = inject(PayerCatalogueService);

  readonly view = signal<'open' | 'closed'>('open');
  readonly rows = signal<OpenPayerShare[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly busy = signal<string | null>(null);
  readonly rejecting = signal<string | null>(null);
  readonly rejectReason = signal('');
  /** Every share the filters find, all pages together (the server's count and total). */
  readonly count = signal(0);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly totalPages = signal(0);

  /* Filters (insurer claims PR 1, G2). */
  readonly payers = signal<Payer[]>([]);
  readonly payerId = signal('');
  readonly planId = signal('');
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly plans = computed<PayerPlan[]>(
    () => this.payers().find((payer) => payer.id === this.payerId())?.plans ?? [],
  );
  readonly periodReversed = computed(
    () => !!this.fromDate() && !!this.toDate() && this.toDate() < this.fromDate(),
  );

  private request = 0;

  constructor() {
    this.payerCatalogue
      .list(true)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (payers) => this.payers.set(payers ?? []),
        // The list still works without the payer filter.
        error: () => this.payers.set([]),
      });
    this.load();
  }

  setView(view: 'open' | 'closed'): void {
    if (this.view() === view) return;
    this.view.set(view);
    // Review of #1353: another tab starts from its first page.
    this.load(0);
  }

  setPayer(payerId: string): void {
    this.payerId.set(payerId ?? '');
    this.planId.set('');
  }

  applyFilters(): void {
    if (this.periodReversed()) {
      const problem = this.translate.instant('INSURER_CLAIMS.SHARES.PERIOD_REVERSED');
      refuseFields(this.host.nativeElement, ['payer-shares-to'], ['payer-shares-to'], problem);
      this.toast.error(problem);
      return;
    }
    this.load(0);
  }

  clearFilters(): void {
    this.payerId.set('');
    this.planId.set('');
    this.fromDate.set('');
    this.toDate.set('');
    this.load(0);
  }

  goTo(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.page()) return;
    this.load(page);
  }

  load(page = this.page()): void {
    const seq = ++this.request;
    const statuses: PayerShareStatus[] =
      this.view() === 'open'
        ? ['OPEN', 'CLAIMED', 'PARTIALLY_PAID']
        : ['PAID', 'REJECTED', 'WRITTEN_OFF'];
    this.loading.set(true);
    this.failed.set(false);
    this.billing
      .searchHospitalPayerShares({
        statuses,
        payerId: this.payerId() || null,
        planId: this.planId() || null,
        fromDate: this.fromDate() || null,
        toDate: this.toDate() || null,
        page,
        size: PayerSharesListComponent.PAGE_SIZE,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (seq !== this.request) return;
          const pages = result?.totalPages ?? 0;
          // Review of #1353: the list shrank under this page (the last row of the final page was settled, or
          // another desk settled shares): read the last page that still exists.
          if (!result?.content?.length && page > 0 && pages > 0 && page >= pages) {
            this.load(pages - 1);
            return;
          }
          this.rows.set(result?.content ?? []);
          this.count.set(result?.totalElements ?? 0);
          this.total.set(result?.totalAmount ?? 0);
          this.page.set(result?.page ?? 0);
          this.totalPages.set(result?.totalPages ?? 0);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.request) return;
          this.rows.set([]);
          this.count.set(0);
          this.total.set(0);
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  /** The payer's refusal of a share not yet on a sent bordereau; a claimed one is refused line by line there. */
  canReject(row: OpenPayerShare): boolean {
    return this.canDecide && row.status === 'OPEN';
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
