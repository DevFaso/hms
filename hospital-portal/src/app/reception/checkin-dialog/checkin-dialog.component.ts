import { ClockTimePipe } from '../../shared/pipes/clock-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import {
  Component,
  Output,
  EventEmitter,
  Input,
  computed,
  ElementRef,
  inject,
  signal,
  ChangeDetectionStrategy,
  OnInit,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  ReceptionService,
  ReceptionQueueItem,
  CheckInRequest,
  CheckInResponse,
  InsuranceSummary,
} from '../reception.service';
import { CfaPipe } from '../../shared/pipes/cfa.pipe';
import { DayMonthPipe } from '../../shared/pipes/day-month.pipe';
import { ToastService } from '../../core/toast.service';
import { BillingService } from '../../services/billing.service';
import { refusalReason } from '../../shared/refusal-reason';
import {
  newPaymentRequestKey,
  paymentOutcomeUnknown,
  retryPaymentWithSameKey,
} from '../../shared/payment-request-key';
import { saveResponse } from '../../shared/download-file';
import { DESK_PAYMENT_METHODS, deskMethodNeedsReference } from '../desk-payment-methods';

import { joinReasons } from '../../shared/required-fields';
import { FieldRefusal, refuseFields } from '../../shared/forms/invalid-fields';
import { CFA_WHOLE_AMOUNT_KEY } from '../../shared/cfa-amount';
import {
  MOBILE_MONEY_OPERATORS,
  MobileMoneyOperator,
  containsCardNumber,
  isMobileMoney,
} from '../../shared/mobile-money-operator';

/** The fields the check-in may refuse (QA D1306, D1320). */
const REFUSABLE_IDS = [
  'checkinIdentityConfirmed',
  'copayAmount',
  'copayOperator',
  'copayReference',
];

/**
 * QA D1320 (round 29): what is wrong with a typed copay — nothing when blank
 * or 0 (nothing to collect), else a negative amount or one with cents.
 */
export function copayAmountMessageKey(amount: number | string | null | undefined): string | null {
  if (amount === null || amount === undefined || amount === '') return null;
  const value = typeof amount === 'number' ? amount : Number(amount);
  if (!Number.isFinite(value) || value < 0) return 'RECEPTION.COPAY_AMOUNT_NEGATIVE';
  return Number.isInteger(value) ? null : CFA_WHOLE_AMOUNT_KEY;
}

@Component({
  selector: 'app-checkin-dialog',
  standalone: true,
  imports: [
    ClockTimePipe,
    DateTimePipe,
    ServerTimePipe,
    DialogFocusDirective,
    FormsModule,
    TranslateModule,
    CfaPipe,
    DayMonthPipe,
  ],
  templateUrl: './checkin-dialog.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './checkin-dialog.component.scss',
})
export class CheckinDialogComponent implements OnInit {
  @Input() queueItem: ReceptionQueueItem | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() checkedIn = new EventEmitter<CheckInResponse>();

  private readonly receptionService = inject(ReceptionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly billing = inject(BillingService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /**
   * QA D3258 (r70): the coverage the desk verifies (payer, plan, policy, validity) and the patient's share of the
   * consultation, read from the patient's front-desk snapshot by the invoice's coverage rule. Null while loading,
   * or when the snapshot cannot be read (the check-in still works).
   */
  readonly coverage = signal<InsuranceSummary | null>(null);
  readonly coverageLoaded = signal(false);
  readonly hasCoverage = computed(() => {
    const c = this.coverage();
    return !!c && (!!c.insuranceId || !!c.primaryPayer || !!c.payerName);
  });

  ngOnInit(): void {
    const patientId = this.queueItem?.patientId;
    if (!patientId) {
      this.coverageLoaded.set(true);
      return;
    }
    this.receptionService.getPatientSnapshot(patientId).subscribe({
      next: (snapshot) => {
        this.coverage.set(snapshot?.insurance ?? null);
        this.coverageLoaded.set(true);
      },
      error: () => this.coverageLoaded.set(true),
    });
  }

  /** QA D3258: takes the suggested patient share as the copay; the desk may still change it. */
  useSuggestedCopay(): void {
    const suggested = this.coverage()?.suggestedCopay;
    if (suggested !== null && suggested !== undefined) {
      this.copayAmount.set(suggested);
    }
  }

  /* ── Form fields ────────────────────────── */
  chiefComplaint = signal('');
  copayAmount = signal<number | null>(null);
  /** QA D975: the copay used to be recorded as cash, always, with no reference. */
  copayMethod = signal<string>('CASH');
  copayReference = signal('');
  /** Billing plan PR 9: Orange Money and Moov Money are totalled apart at the close. */
  copayOperator = signal<MobileMoneyOperator | null>(null);
  readonly mobileMoneyOperators = MOBILE_MONEY_OPERATORS;
  readonly copayIsMobileMoney = computed(() => isMobileMoney(this.copayMethod()));
  readonly methods = DESK_PAYMENT_METHODS;
  readonly copayNeedsReference = computed(() => deskMethodNeedsReference(this.copayMethod()));
  identityConfirmed = signal(false);
  insuranceVerified = signal(false);
  /* Consent-to-treat (P3 #21): recorded, never gating — the submit does not
     require it, unlike identity confirmation. */
  consentObtained = signal(false);
  consentSignedName = signal('');
  notes = signal('');
  saving = signal(false);
  /**
   * Billing plan PR 11 (QA D2430): one key per opening of this dialog (it is created anew each time), sent
   * with the copay on every press, so a check-in whose answer was lost is answered again, not refused.
   */
  readonly copayKey = newPaymentRequestKey();

  /**
   * QA D1591: the patient consented to care at pre-check-in; the box shows it
   * ticked and fixed, and the check-in carries that consent onto the visit
   * (the server links it) instead of recording a second one.
   */
  get consentGivenAtPreCheckIn(): boolean {
    return !!this.queueItem?.preCheckInConsent;
  }

  get canSubmit(): boolean {
    return !!this.queueItem?.appointmentId && this.refusals().length === 0 && !this.saving();
  }

  /**
   * QA D1320 (round 29; the D1342 / D1256 class): every refused field of the
   * check-in at once, each under its own reason — the identity tick, a copay
   * that is negative or has cents (the client posted both and the server
   * answered with a toast), and the payment reference. A blank copay is
   * fine: there is nothing to collect. Empty: it may be sent.
   */
  refusals(): FieldRefusal[] {
    const out: FieldRefusal[] = [];
    if (!this.identityConfirmed()) {
      out.push({
        id: 'checkinIdentityConfirmed',
        message: this.translate.instant('RECEPTION.CONFIRM_IDENTITY_FIRST'),
      });
    }
    const amountKey = copayAmountMessageKey(this.copayAmount());
    if (amountKey) out.push({ id: 'copayAmount', message: this.translate.instant(amountKey) });
    const copayTaken = (this.copayAmount() ?? 0) > 0;
    if (copayTaken && this.copayIsMobileMoney() && !this.copayOperator()) {
      out.push({
        id: 'copayOperator',
        message: this.translate.instant('MOBILE_MONEY.OPERATOR_REQUIRED'),
      });
    }
    const referenceMissing =
      copayTaken && this.copayNeedsReference() && !this.copayReference().trim();
    if (referenceMissing) {
      out.push({
        id: 'copayReference',
        message: this.translate.instant('SUBMIT_GUARD.PAYMENT_REFERENCE'),
      });
    } else if (copayTaken && containsCardNumber(this.copayReference())) {
      // Owner decision D-15: never a card number (the server refuses it too).
      out.push({
        id: 'copayReference',
        message: this.translate.instant('MOBILE_MONEY.CARD_NUMBER_REFUSED'),
      });
    }
    return out;
  }

  submit(): void {
    // QA D1306 (round 28) / D1320 (round 29): the refused fields are marked,
    // not only a toast, and all of them on one press.
    const refusals = this.refusals();
    refuseFields(this.host.nativeElement, refusals, REFUSABLE_IDS);
    const appointmentId = this.queueItem?.appointmentId;
    if (!appointmentId || refusals.length > 0) {
      this.toast.error(
        joinReasons([
          !appointmentId && this.translate.instant('RECEPTION.NO_APPOINTMENT_SELECTED'),
          ...refusals.map((r) => r.message),
        ]) ?? '',
      );
      return;
    }

    this.saving.set(true);

    const request: CheckInRequest = {
      appointmentId,
      chiefComplaint: this.chiefComplaint() || null,
      copayAmount: this.copayAmount(),
      ...((this.copayAmount() ?? 0) > 0
        ? {
            copayMethod: this.copayMethod(),
            copayReference: this.copayReference().trim() || null,
            ...(this.copayIsMobileMoney()
              ? { copayMobileMoneyOperator: this.copayOperator() }
              : {}),
            ...(this.copayKey ? { copayClientRequestId: this.copayKey } : {}),
          }
        : {}),
      identityConfirmed: this.identityConfirmed(),
      insuranceVerified: this.insuranceVerified(),
      notes: this.notes() || null,
    };
    if (this.consentObtained() && !this.consentGivenAtPreCheckIn) {
      request.consentObtained = true;
      request.consentMethod = 'ELECTRONIC';
      request.consentSignedName = this.consentSignedName().trim() || null;
    }

    // Only a check-in that takes a copay carries a key, so only that one is sent again by itself.
    const key = request.copayClientRequestId ?? undefined;
    this.receptionService
      .checkInPatient(request)
      .pipe(retryPaymentWithSameKey(key))
      .subscribe({
        next: (response) => {
          this.saving.set(false);
          // The server's `message` is English prose; the toast speaks the
          // user's language instead (D121).
          this.toast.success(this.translate.instant('RECEPTION.CHECK_IN_SUCCESS'));
          this.downloadCopayReceipt(response);
          this.checkedIn.emit(response);
        },
        error: (err) => {
          this.saving.set(false);
          if (key && paymentOutcomeUnknown(err)) {
            // QA D2430: no answer is not a failure; pressing again with the same key records nothing twice.
            this.toast.warning(this.translate.instant('PAYMENT_OUTCOME.CHECK_IN_UNKNOWN'));
          } else {
            this.toast.error(
              refusalReason(err) ?? this.translate.instant('RECEPTION.CHECK_IN_FAILED'),
            );
          }
        },
      });
  }

  /** QA D975: the copay's receipt, handed to the patient at the desk. */
  private downloadCopayReceipt(response: CheckInResponse): void {
    if (!response.copayInvoiceId || !response.copayPaymentId) return;
    this.billing.getReceiptPdf(response.copayInvoiceId, response.copayPaymentId).subscribe({
      // QA D1009 (#973): the receipt keeps the server's name (its receipt number);
      // the encounter code is only the fallback, never the database id (D1009).
      next: (res) =>
        saveResponse(
          res,
          response.encounterCode ? `recu-${response.encounterCode}.pdf` : 'recu.pdf',
        ),
      error: (err: unknown) =>
        this.toast.error(refusalReason(err) ?? this.translate.instant('RECEPTION.RECEIPT_FAILED')),
    });
  }
}
