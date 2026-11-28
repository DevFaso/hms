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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, map } from 'rxjs';

import { FormControlStyleDirective } from '../../shared/forms/form-control-style.directive';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { BillingService, OpenPayerShare } from '../../services/billing.service';
import {
  ClaimBatch,
  ClaimLine,
  ClaimRefusalClass,
  ClaimRefusalResolution,
  ClaimWriteOffReason,
  InsurerClaimsService,
  REFUSAL_RESOLUTIONS,
} from '../../services/insurer-claims.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { formatCfa } from '../../shared/i18n/app-locale';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { KeyLabelPipe } from '../../shared/pipes/key-label.pipe';
import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { containsCardNumber } from '../../shared/mobile-money-operator';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { saveResponse } from '../../shared/download-file';
import { allPages } from '../../shared/all-pages';
import { newPaymentRequestKey } from '../../shared/payment-request-key';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { localDateString } from '../../shared/date-utils';
import { ClaimsNavComponent } from './claims-nav.component';

/** A whole number of francs typed in a field, or null when it is not one. */
export function wholeFrancs(raw: string | number | null | undefined): number | null {
  const text = String(raw ?? '')
    .replace(/[\s\u00a0\u202f]/g, '')
    .trim();
  if (!/^\d+$/.test(text)) return null;
  const value = Number(text);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

/**
 * Insurer claims PRs 2 to 4 (QA D3983): one bordereau. As a draft: its lines with what to check before sending,
 * the open shares of the payer that can still be added, the documents attached to each act, then « Envoyer ». Once
 * sent: its number and dates, the PDF and CSV for the payer, the day the payer received it, and line by line what
 * the payer paid or refused, with the refusal form (the class of the reason decides what may follow) and the
 * write-off request. The server checks every step again.
 */
@Component({
  selector: 'app-claim-batch-detail',
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
    SubmitGuardDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './claim-batch-detail.component.html',
  styleUrl: './insurer-claims.scss',
})
export class ClaimBatchDetailComponent {
  private readonly claims = inject(InsurerClaimsService);
  private readonly billing = inject(BillingService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly confirm = inject(ConfirmService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly canBuild = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
    'ROLE_BILLING_SPECIALIST',
    'ROLE_CLAIMS_REVIEWER',
  ]);
  /** Hospital admin and accountant: cancel a sent bordereau, re-bill a refused amount to the patient. */
  readonly isFinance = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_ACCOUNTANT',
  ]);

  readonly classes: ClaimRefusalClass[] = [
    'PATIENT_SIDE',
    'PROVIDER_FAULT',
    'TARIFF_REDUCTION',
    'DISPUTED',
  ];
  readonly writeOffReasons: ClaimWriteOffReason[] = [
    'IRRECOVERABLE_AFTER_RECOVERY',
    'COMMERCIAL_WAIVER',
    'CONTRACTUAL_ADJUSTMENT',
  ];

  private readonly batchId = this.route.snapshot.paramMap.get('id') ?? '';
  readonly batch = signal<ClaimBatch | null>(null);
  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly busy = signal(false);

  readonly isDraft = computed(() => this.batch()?.status === 'DRAFT');
  readonly isSent = computed(() =>
    ['SUBMITTED', 'PARTIALLY_SETTLED', 'SETTLED'].includes(this.batch()?.status ?? ''),
  );
  readonly lines = computed<ClaimLine[]>(() => this.batch()?.lines ?? []);

  /* Draft: the payer's open shares not on any bordereau. */
  readonly available = signal<OpenPayerShare[]>([]);
  readonly documents = signal<Record<string, string>>({});

  /* Sending. */
  readonly submittedOn = signal(localDateString());
  readonly payerReference = signal('');
  readonly receivedOn = signal('');
  readonly cancelling = signal(false);
  readonly cancelReason = signal('');

  /* The payer's answer on one line. */
  readonly acting = signal<{ lineId: string; mode: 'refuse' | 'writeOff' } | null>(null);
  readonly amount = signal('');
  readonly reasonClass = signal<ClaimRefusalClass>('PATIENT_SIDE');
  readonly resolution = signal<ClaimRefusalResolution>('RESUBMIT');
  readonly reason = signal('');
  readonly writeOffReason = signal<ClaimWriteOffReason>('COMMERCIAL_WAIVER');
  readonly supportingDocument = signal('');
  private requestKey = newPaymentRequestKey();

  /** What may follow a refusal of the chosen class; re-billing the patient is for finance only. */
  readonly resolutions = computed<ClaimRefusalResolution[]>(() =>
    REFUSAL_RESOLUTIONS[this.reasonClass()].filter(
      (choice) => choice !== 'REBILL_PATIENT' || this.isFinance,
    ),
  );
  readonly needsWriteOff = computed(
    () => this.acting()?.mode === 'writeOff' || this.resolution() === 'WRITE_OFF_REQUEST',
  );

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.claims
      .getBatch(this.batchId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (batch) => {
          this.show(batch);
          this.loading.set(false);
        },
        error: () => {
          this.failed.set(true);
          this.loading.set(false);
        },
      });
  }

  private show(batch: ClaimBatch): void {
    this.batch.set(batch);
    const documents: Record<string, string> = {};
    for (const line of batch.lines ?? []) documents[line.id] = line.attachedDocuments ?? '';
    this.documents.set(documents);
    this.receivedOn.set(batch.payerReceivedOn ?? '');
    if (batch.status === 'DRAFT') this.loadAvailable(batch);
  }

  private loadAvailable(batch: ClaimBatch): void {
    // Every page of the payer's open shares: a share on the second page can be claimed too.
    allPages<OpenPayerShare>(
      (page, size) =>
        this.billing
          .searchHospitalPayerShares({ statuses: ['OPEN'], payerId: batch.payerId, page, size })
          .pipe(map((result) => ({ ...result, number: result?.page }))),
      100,
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (all) => this.available.set(all.rows.filter((share) => !share.claimBatchId)),
        error: () => this.available.set([]),
      });
  }

  /** Runs one write, shows its answer (the bordereau as it now stands) and tells the user. */
  private run(call: Observable<ClaimBatch>, doneKey: string, after?: () => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (batch) => {
        this.busy.set(false);
        this.show(batch);
        this.toast.success(this.translate.instant(doneKey));
        after?.();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.BATCH.FAILED'),
        );
      },
    });
  }

  private refuse(fieldIds: string[], key: string): void {
    const problem = this.translate.instant(key);
    refuseFields(this.host.nativeElement, fieldIds, fieldIds, problem);
    this.toast.error(problem);
  }

  /* ── Draft ── */

  addShare(share: OpenPayerShare): void {
    this.run(
      this.claims.changeShares(this.batchId, { add: [share.id] }),
      'INSURER_CLAIMS.BATCH.SHARE_ADDED',
    );
  }

  async removeShare(line: ClaimLine): Promise<void> {
    const sure = await this.confirm.ask({
      titleKey: 'INSURER_CLAIMS.BATCH.REMOVE_CONFIRM',
      params: { invoice: line.invoiceNumber ?? '' },
      confirmKey: 'INSURER_CLAIMS.BATCH.REMOVE_SHARE',
    });
    if (!sure) return;
    this.run(
      this.claims.changeShares(this.batchId, { remove: [line.shareId] }),
      'INSURER_CLAIMS.BATCH.SHARE_REMOVED',
    );
  }

  setDocuments(line: ClaimLine, value: string): void {
    this.documents.update((all) => ({ ...all, [line.id]: value ?? '' }));
  }

  saveDocuments(line: ClaimLine): void {
    const value = (this.documents()[line.id] ?? '').trim();
    if ((line.attachedDocuments ?? '') === value) return;
    this.run(
      this.claims.setLineDocuments(this.batchId, line.id, value),
      'INSURER_CLAIMS.BATCH.DOCUMENTS_SAVED',
    );
  }

  async submit(): Promise<void> {
    const batch = this.batch();
    if (!batch) return;
    if (!this.submittedOn()) {
      this.refuse(['claim-submitted-on'], 'INSURER_CLAIMS.BATCH.DATE_REQUIRED');
      return;
    }
    const sure = await this.confirm.ask({
      titleKey: 'INSURER_CLAIMS.BATCH.SUBMIT_CONFIRM',
      messageKey: batch.warningCount ? 'INSURER_CLAIMS.BATCH.SUBMIT_WARNINGS' : null,
      params: {
        payer: batch.payerName ?? '',
        amount: formatCfa(batch.claimedTotal, 'XOF'),
        count: batch.warningCount ?? 0,
      },
      confirmKey: 'INSURER_CLAIMS.BATCH.SUBMIT',
    });
    if (!sure) return;
    this.run(
      this.claims.submitBatch(this.batchId, {
        submittedOn: this.submittedOn(),
        payerReference: this.payerReference().trim() || null,
      }),
      'INSURER_CLAIMS.BATCH.SUBMITTED',
    );
  }

  async deleteDraft(): Promise<void> {
    const sure = await this.confirm.ask({
      titleKey: 'INSURER_CLAIMS.BATCH.DELETE_CONFIRM',
      confirmKey: 'COMMON.DELETE',
    });
    if (!sure || this.busy()) return;
    this.busy.set(true);
    this.claims
      .deleteDraft(this.batchId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('INSURER_CLAIMS.BATCH.DELETED'));
          void this.router.navigate(['/billing/bordereaux']);
        },
        error: (err: unknown) => {
          this.busy.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.BATCH.FAILED'),
          );
        },
      });
  }

  /* ── Sent ── */

  export(format: 'pdf' | 'csv'): void {
    const name = `bordereau-${this.batch()?.number ?? 'brouillon'}.${format}`;
    this.claims
      .exportBatch(this.batchId, format)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => saveResponse(response, name),
        error: (err: unknown) =>
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.BATCH.EXPORT_FAILED'),
          ),
      });
  }

  saveReceipt(): void {
    if (!this.receivedOn()) {
      this.refuse(['claim-received-on'], 'INSURER_CLAIMS.BATCH.DATE_REQUIRED');
      return;
    }
    this.run(
      this.claims.recordPayerReceipt(this.batchId, { receivedOn: this.receivedOn() }),
      'INSURER_CLAIMS.BATCH.RECEIPT_SAVED',
    );
  }

  async confirmCancel(): Promise<void> {
    const reason = this.cancelReason().trim();
    if (!reason) {
      this.refuse(['claim-cancel-reason'], 'INSURER_CLAIMS.BATCH.REASON_REQUIRED');
      return;
    }
    const sure = await this.confirm.ask({
      titleKey: 'INSURER_CLAIMS.BATCH.CANCEL_CONFIRM',
      params: { number: this.batch()?.number ?? '' },
      confirmKey: 'INSURER_CLAIMS.BATCH.CANCEL',
    });
    if (!sure) return;
    this.run(this.claims.cancelBatch(this.batchId, reason), 'INSURER_CLAIMS.BATCH.CANCELLED', () =>
      this.cancelling.set(false),
    );
  }

  /** Asks for a write-off of {@code amount} of the line; another person will decide it. */
  private askWriteOff(
    line: ClaimLine,
    amount: number,
    reason: string,
    document: string,
    key: string,
  ): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.claims
      .requestWriteOff(line.id, {
        amount,
        reasonCode: this.writeOffReason(),
        reason,
        supportingDocument: document,
        clientRequestId: key,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.acting.set(null);
          this.toast.success(this.translate.instant('INSURER_CLAIMS.BATCH.WRITE_OFF_ASKED'));
          this.load();
        },
        error: (err: unknown) => {
          this.busy.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('INSURER_CLAIMS.BATCH.FAILED'),
          );
        },
      });
  }

  /* ── The payer's answer on a line ── */

  canAct(line: ClaimLine): boolean {
    return this.canBuild && this.isSent() && line.availableAmount > 0;
  }

  start(line: ClaimLine, mode: 'refuse' | 'writeOff'): void {
    this.acting.set({ lineId: line.id, mode });
    this.amount.set(String(line.availableAmount));
    this.reasonClass.set('PATIENT_SIDE');
    this.resolution.set('RESUBMIT');
    this.reason.set('');
    this.writeOffReason.set('COMMERCIAL_WAIVER');
    this.supportingDocument.set('');
    this.requestKey = newPaymentRequestKey();
  }

  stop(): void {
    this.acting.set(null);
  }

  setClass(value: ClaimRefusalClass): void {
    this.reasonClass.set(value);
    // The first choice the class allows: never a re-billing the reader did not ask for.
    const allowed = this.resolutions();
    const safe = allowed.find((choice) => choice !== 'REBILL_PATIENT') ?? allowed[0];
    this.resolution.set(safe);
    if (value === 'TARIFF_REDUCTION') this.writeOffReason.set('CONTRACTUAL_ADJUSTMENT');
  }

  async confirmAction(line: ClaimLine): Promise<void> {
    const acting = this.acting();
    if (!acting) return;
    const amount = wholeFrancs(this.amount());
    if (amount == null || amount > line.availableAmount) {
      this.refuse(['claim-action-amount'], 'INSURER_CLAIMS.BATCH.AMOUNT_INVALID');
      return;
    }
    const reason = this.reason().trim();
    const document = this.supportingDocument().trim();
    // Every blank required field is named at once, not one press at a time.
    const missing: string[] = [];
    if (!reason) missing.push('claim-action-reason');
    if (this.needsWriteOff() && !document) missing.push('claim-action-document');
    if (missing.length > 0) {
      this.refuse(missing, 'INSURER_CLAIMS.BATCH.ACTION_REQUIRED');
      return;
    }
    if (containsCardNumber(reason) || containsCardNumber(document)) {
      this.refuse(['claim-action-reason'], 'MOBILE_MONEY.CARD_NUMBER_REFUSED');
      return;
    }
    const key = this.requestKey ?? '';
    if (acting.mode === 'writeOff') {
      this.askWriteOff(line, amount, reason, document, key);
      return;
    }
    if (this.resolution() === 'REBILL_PATIENT') {
      // The patient will owe this amount: a deliberate step, asked once more.
      const sure = await this.confirm.ask({
        titleKey: 'INSURER_CLAIMS.BATCH.REBILL_CONFIRM',
        params: { amount: formatCfa(amount, 'XOF'), patient: line.patientName ?? '' },
        confirmKey: 'INSURER_CLAIMS.RESOLUTION.REBILL_PATIENT',
      });
      if (!sure) return;
    }
    this.run(
      this.claims.refuseLine(line.id, {
        amount,
        reasonClass: this.reasonClass(),
        reason,
        resolution: this.resolution(),
        writeOffReason: this.resolution() === 'WRITE_OFF_REQUEST' ? this.writeOffReason() : null,
        supportingDocument: this.resolution() === 'WRITE_OFF_REQUEST' ? document : null,
        clientRequestId: key,
      }),
      'INSURER_CLAIMS.BATCH.REFUSAL_SAVED',
      () => this.acting.set(null),
    );
  }
}
