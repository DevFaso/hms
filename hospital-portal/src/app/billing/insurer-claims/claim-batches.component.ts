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
import { Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { Payer, PayerCatalogueService } from '../../services/payer-catalogue.service';
import {
  ClaimBatch,
  ClaimBatchStatus,
  InsurerClaimsService,
} from '../../services/insurer-claims.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { newPaymentRequestKey } from '../../shared/payment-request-key';
import { ClaimsNavComponent } from './claims-nav.component';

/** The first and last day of the month before {@code today}, as ISO dates: the usual claim period. */
export function previousMonth(today: Date): { from: string; to: string } {
  const first = new Date(Date.UTC(today.getFullYear(), today.getMonth() - 1, 1));
  const last = new Date(Date.UTC(today.getFullYear(), today.getMonth(), 0));
  return { from: first.toISOString().slice(0, 10), to: last.toISOString().slice(0, 10) };
}

/**
 * Insurer claims PR 2 (QA D3983): « Bordereaux », the claim sheets the hospital sends to its payers. One list (in
 * progress or closed), and a short form that builds a draft for one payer and one period: the server proposes
 * every open share of that payer whose service date is in the period. The roles that build a bordereau see the
 * form; the super-admin reads only.
 */
@Component({
  selector: 'app-claim-batches',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    FormControlStyleDirective,
    CfaPipe,
    DayMonthPipe,
    KeyLabelPipe,
    ClaimsNavComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './claim-batches.component.html',
  styleUrl: './insurer-claims.scss',
})
export class ClaimBatchesComponent {
  static readonly PAGE_SIZE = 25;

  private readonly claims = inject(InsurerClaimsService);
  private readonly payerCatalogue = inject(PayerCatalogueService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly canBuild = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_CLAIMS_REVIEWER',
  ]);

  readonly view = signal<'open' | 'closed'>('open');
  readonly rows = signal<ClaimBatch[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly payers = signal<Payer[]>([]);
  readonly filterPayerId = signal('');

  /* The new draft. */
  readonly payerId = signal('');
  readonly periodFrom = signal(previousMonth(new Date()).from);
  readonly periodTo = signal(previousMonth(new Date()).to);
  readonly creating = signal(false);
  readonly periodReversed = computed(
    () => !!this.periodFrom() && !!this.periodTo() && this.periodTo() < this.periodFrom(),
  );
  /** One key per draft asked for: a double click builds one bordereau. */
  private requestKey = newPaymentRequestKey();
  private request = 0;

  constructor() {
    this.payerCatalogue
      .list(true)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (payers) => this.payers.set(payers ?? []),
        error: () => this.payers.set([]),
      });
    this.load(0);
  }

  setView(view: 'open' | 'closed'): void {
    if (this.view() === view) return;
    this.view.set(view);
    this.load(0);
  }

  setFilterPayer(payerId: string): void {
    this.filterPayerId.set(payerId ?? '');
    this.load(0);
  }

  goTo(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.page()) return;
    this.load(page);
  }

  load(page = this.page()): void {
    const seq = ++this.request;
    const statuses: ClaimBatchStatus[] =
      this.view() === 'open'
        ? ['DRAFT', 'SUBMITTED', 'PARTIALLY_SETTLED']
        : ['SETTLED', 'CANCELLED'];
    this.loading.set(true);
    this.failed.set(false);
    this.claims
      .listBatches({
        statuses,
        payerId: this.filterPayerId() || null,
        page,
        size: ClaimBatchesComponent.PAGE_SIZE,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (seq !== this.request) return;
          this.rows.set(result?.content ?? []);
          this.page.set(result?.page ?? 0);
          this.totalPages.set(result?.totalPages ?? 0);
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

  create(): void {
    if (this.creating()) return;
    const missing: string[] = [];
    if (!this.payerId()) missing.push('claim-batch-payer');
    if (!this.periodFrom()) missing.push('claim-batch-from');
    if (!this.periodTo()) missing.push('claim-batch-to');
    if (missing.length > 0) {
      const problem = this.translate.instant('INSURER_CLAIMS.BATCHES.NEW_REQUIRED');
      refuseFields(this.host.nativeElement, missing, missing, problem);
      this.toast.error(problem);
      return;
    }
    if (this.periodReversed()) {
      const problem = this.translate.instant('INSURER_CLAIMS.SHARES.PERIOD_REVERSED');
      refuseFields(this.host.nativeElement, ['claim-batch-to'], ['claim-batch-to'], problem);
      this.toast.error(problem);
      return;
    }
    this.creating.set(true);
    this.claims
      .createBatch({
        payerId: this.payerId(),
        periodFrom: this.periodFrom(),
        periodTo: this.periodTo(),
        clientRequestId: this.requestKey ?? '',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (batch) => {
          this.creating.set(false);
          this.requestKey = newPaymentRequestKey();
          this.toast.success(
            this.translate.instant('INSURER_CLAIMS.BATCHES.CREATED', {
              count: batch.lineCount ?? 0,
            }),
          );
          void this.router.navigate(['/billing/bordereaux', batch.id]);
        },
        error: (err: unknown) => {
          this.creating.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.BATCHES.CREATE_FAILED'),
          );
        },
      });
  }
}
