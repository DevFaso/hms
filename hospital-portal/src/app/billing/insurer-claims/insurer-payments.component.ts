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

import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { Payer, PayerCatalogueService } from '../../services/payer-catalogue.service';
import {
  AllocationRequest,
  ClaimLine,
  InsurerClaimsService,
  InsurerPayment,
  InsurerPaymentMethod,
} from '../../services/insurer-claims.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { containsCardNumber } from '../../shared/mobile-money-operator';
import { newPaymentRequestKey } from '../../shared/payment-request-key';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { localDateString } from '../../shared/date-utils';
import { ClaimsNavComponent } from './claims-nav.component';
import { wholeFrancs } from './claim-batch-detail.component';

/**
 * Spreads {@code amount} over the lines in the order given (the oldest bordereau first), each line taking at most
 * what it still expects: the allocation the screen proposes, which the accountant may change.
 */
export function proposeAllocation(lines: ClaimLine[], amount: number): Record<string, string> {
  const out: Record<string, string> = {};
  let left = Math.max(0, amount);
  for (const line of lines) {
    const take = Math.min(left, Math.max(0, line.availableAmount));
    if (take > 0) out[line.id] = String(take);
    left -= take;
  }
  return out;
}

/**
 * Insurer claims PR 3 (QA D3983): « Règlements assureurs ». A payer's remittance is recorded with its amount, the
 * day it was received, how and under which reference, then put on bordereau lines of that payer, wholly or in part;
 * what is not allocated stays visible as « à affecter ». Recorded by the hospital admin or the accountant only,
 * never through a till; a payment entered in error is reversed, with a reason, by another person.
 */
/** The controls a server field names, by the field (QA D1341). */
const FIELD_IDS: Readonly<Record<string, string>> = {
  payerId: 'insurer-payment-payer',
  amount: 'insurer-payment-amount',
  receivedDate: 'insurer-payment-date',
  method: 'insurer-payment-method',
  reference: 'insurer-payment-reference',
  reason: 'insurer-reverse-reason',
};

@Component({
  selector: 'app-insurer-payments',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    FormControlStyleDirective,
    CfaPipe,
    DayMonthPipe,
    KeyLabelPipe,
    ClaimsNavComponent,
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './insurer-payments.component.html',
  styleUrl: './insurer-claims.scss',
})
export class InsurerPaymentsComponent {
  static readonly PAGE_SIZE = 25;

  private readonly claims = inject(InsurerClaimsService);
  private readonly payerCatalogue = inject(PayerCatalogueService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Hospital admin and accountant record, allocate and reverse; the other claims roles read. */
  readonly isFinance = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
  ]);
  readonly methods: InsurerPaymentMethod[] = ['BANK_TRANSFER', 'CHEQUE', 'MOBILE_MONEY', 'CASH'];

  readonly payers = signal<Payer[]>([]);
  readonly rows = signal<InsurerPayment[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly busy = signal(false);

  /* The payment being recorded, or the recorded payment being allocated. */
  readonly mode = signal<'idle' | 'record' | 'allocate'>('idle');
  readonly target = signal<InsurerPayment | null>(null);
  readonly payerId = signal('');
  readonly amount = signal('');
  readonly receivedDate = signal(localDateString());
  readonly method = signal<InsurerPaymentMethod>('BANK_TRANSFER');
  readonly reference = signal('');
  readonly note = signal('');
  readonly openLines = signal<ClaimLine[]>([]);
  readonly allocation = signal<Record<string, string>>({});
  private requestKey = newPaymentRequestKey();

  /* Reversal. */
  readonly reversing = signal<string | null>(null);
  readonly reversalReason = signal('');

  /** What the grid may hand out: the new payment's amount, or what is left of the recorded one. */
  readonly budget = computed(() =>
    this.mode() === 'allocate'
      ? (this.target()?.unallocatedAmount ?? 0)
      : (wholeFrancs(this.amount()) ?? 0),
  );
  readonly allocated = computed(() =>
    Object.values(this.allocation()).reduce((sum, raw) => sum + (wholeFrancs(raw) ?? 0), 0),
  );
  readonly unallocated = computed(() => this.budget() - this.allocated());

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

  goTo(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.page()) return;
    this.load(page);
  }

  load(page = this.page()): void {
    const seq = ++this.request;
    this.loading.set(true);
    this.failed.set(false);
    this.claims
      .listPayments({ page, size: InsurerPaymentsComponent.PAGE_SIZE })
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

  /* ── Record ── */

  startRecord(): void {
    this.mode.set('record');
    this.target.set(null);
    this.payerId.set('');
    this.amount.set('');
    this.reference.set('');
    this.note.set('');
    this.method.set('BANK_TRANSFER');
    this.receivedDate.set(localDateString());
    this.openLines.set([]);
    this.allocation.set({});
    this.requestKey = newPaymentRequestKey();
  }

  startAllocate(payment: InsurerPayment): void {
    this.mode.set('allocate');
    this.target.set(payment);
    this.allocation.set({});
    this.loadOpenLines(payment.payerId);
  }

  stop(): void {
    this.mode.set('idle');
    this.target.set(null);
  }

  setPayer(payerId: string): void {
    this.payerId.set(payerId ?? '');
    this.allocation.set({});
    this.openLines.set([]);
    if (payerId) this.loadOpenLines(payerId);
  }

  private loadOpenLines(payerId: string): void {
    this.claims
      .openLines(payerId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (lines) => this.openLines.set(Array.isArray(lines) ? lines : []),
        error: () => this.openLines.set([]),
      });
  }

  setAllocation(line: ClaimLine, value: string): void {
    this.allocation.update((all) => ({ ...all, [line.id]: value ?? '' }));
  }

  /** Oldest bordereau first, each line up to what it still expects. */
  propose(): void {
    this.allocation.set(proposeAllocation(this.openLines(), this.budget()));
  }

  private refuse(fieldIds: string[], key: string): void {
    const problem = this.translate.instant(key);
    refuseFields(this.host.nativeElement, fieldIds, fieldIds, problem);
    this.toast.error(problem);
  }

  /** The grid as the server takes it, or null (and the field named) when a cell is wrong. */
  private allocations(): AllocationRequest[] | null {
    const out: AllocationRequest[] = [];
    for (const line of this.openLines()) {
      const raw = (this.allocation()[line.id] ?? '').trim();
      if (!raw) continue;
      const value = wholeFrancs(raw);
      if (value == null || value > line.availableAmount) {
        this.refuse(['insurer-alloc-' + line.id], 'INSURER_CLAIMS.PAYMENTS.ALLOCATION_INVALID');
        return null;
      }
      out.push({ claimLineId: line.id, amount: value });
    }
    if (this.unallocated() < 0) {
      this.refuse(this.filledCells(), 'INSURER_CLAIMS.PAYMENTS.OVER_ALLOCATED');
      return null;
    }
    return out;
  }

  /** The grid cells holding an amount, else the first one: the fields an allocation refusal is about. */
  private filledCells(): string[] {
    const lines = this.openLines();
    const filled = lines.filter((line) => (this.allocation()[line.id] ?? '').trim() !== '');
    return (filled.length > 0 ? filled : lines.slice(0, 1)).map(
      (line) => 'insurer-alloc-' + line.id,
    );
  }

  save(): void {
    if (this.busy()) return;
    if (this.mode() === 'allocate') {
      const target = this.target();
      const allocations = this.allocations();
      if (!target || !allocations) return;
      if (allocations.length === 0) {
        this.refuse(this.filledCells(), 'INSURER_CLAIMS.PAYMENTS.NOTHING_ALLOCATED');
        return;
      }
      this.send(
        this.claims.allocate(target.id, allocations),
        'INSURER_CLAIMS.PAYMENTS.ALLOCATED_TOAST',
      );
      return;
    }
    const missing: string[] = [];
    if (!this.payerId()) missing.push('insurer-payment-payer');
    if (wholeFrancs(this.amount()) == null) missing.push('insurer-payment-amount');
    if (!this.receivedDate()) missing.push('insurer-payment-date');
    if (!this.reference().trim()) missing.push('insurer-payment-reference');
    if (missing.length > 0) {
      this.refuse(missing, 'INSURER_CLAIMS.PAYMENTS.REQUIRED');
      return;
    }
    if (containsCardNumber(this.reference()) || containsCardNumber(this.note())) {
      this.refuse(['insurer-payment-reference'], 'MOBILE_MONEY.CARD_NUMBER_REFUSED');
      return;
    }
    const allocations = this.allocations();
    if (!allocations) return;
    this.send(
      this.claims.recordInsurerPayment({
        payerId: this.payerId(),
        amount: wholeFrancs(this.amount()) ?? 0,
        receivedDate: this.receivedDate(),
        method: this.method(),
        reference: this.reference().trim(),
        note: this.note().trim() || null,
        allocations,
        clientRequestId: this.requestKey ?? '',
      }),
      'INSURER_CLAIMS.PAYMENTS.RECORDED_TOAST',
    );
  }

  private send(
    call: ReturnType<InsurerClaimsService['recordInsurerPayment']>,
    doneKey: string,
  ): void {
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.stop();
        this.reversing.set(null);
        this.toast.success(this.translate.instant(doneKey));
        this.load();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.markRefused(err);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.PAYMENTS.FAILED'),
        );
      },
    });
  }

  /** QA D1341 class: a 400 that names fields marks each control it names with its own reason. */
  private markRefused(err: unknown): void {
    const root = this.host.nativeElement;
    const idByField: Record<string, string> = {};
    for (const [field, id] of Object.entries(FIELD_IDS)) {
      if (root.querySelector(`#${id}`) !== null) idByField[field] = id;
    }
    const refused = refusedFieldReasons(err, idByField);
    if (refused.length > 0) {
      refuseFields(
        root,
        refused.map(([id, message]) => ({ id, message })),
        Object.values(idByField),
      );
    }
  }

  /* ── Reverse ── */

  canReverse(payment: InsurerPayment): boolean {
    return this.isFinance && payment.status === 'RECORDED' && !payment.recordedByMe;
  }

  startReverse(payment: InsurerPayment): void {
    this.reversing.set(payment.id);
    this.reversalReason.set('');
  }

  confirmReverse(payment: InsurerPayment): void {
    const reason = this.reversalReason().trim();
    if (!reason) {
      this.refuse(['insurer-reverse-reason'], 'INSURER_CLAIMS.BATCH.REASON_REQUIRED');
      return;
    }
    if (this.busy()) return;
    this.send(
      this.claims.reversePayment(payment.id, reason),
      'INSURER_CLAIMS.PAYMENTS.REVERSED_TOAST',
    );
  }
}
