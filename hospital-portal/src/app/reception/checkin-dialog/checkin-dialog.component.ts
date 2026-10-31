import { DialogFocusDirective } from '../../shared/a11y/dialog-focus.directive';
import {
  Component,
  Output,
  EventEmitter,
  Input,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  ReceptionService,
  ReceptionQueueItem,
  CheckInRequest,
  CheckInResponse,
} from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { BillingService } from '../../services/billing.service';
import { refusalReason } from '../../shared/refusal-reason';
import { saveResponse } from '../../shared/download-file';
import { DESK_PAYMENT_METHODS, deskMethodNeedsReference } from '../desk-payment-methods';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
@Component({
  selector: 'app-checkin-dialog',
  standalone: true,
  imports: [SubmitGuardDirective, DialogFocusDirective, FormsModule, TranslateModule],
  templateUrl: './checkin-dialog.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './checkin-dialog.component.scss',
})
export class CheckinDialogComponent {
  @Input() queueItem: ReceptionQueueItem | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() checkedIn = new EventEmitter<CheckInResponse>();

  private readonly receptionService = inject(ReceptionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly billing = inject(BillingService);

  /* ── Form fields ────────────────────────── */
  chiefComplaint = signal('');
  copayAmount = signal<number | null>(null);
  /** QA D975: the copay used to be recorded as cash, always, with no reference. */
  copayMethod = signal<string>('CASH');
  copayReference = signal('');
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

  get canSubmit(): boolean {
    const referenceMissing =
      (this.copayAmount() ?? 0) > 0 && this.copayNeedsReference() && !this.copayReference().trim();
    return (
      !!this.queueItem?.appointmentId &&
      this.identityConfirmed() &&
      !referenceMissing &&
      !this.saving()
    );
  }

  /**
   * QA D1124: why "Enregistrer" refuses, shown next to it (null when it may
   * be sent). The button is never silently disabled.
   */
  submitBlockReason(): string | null {
    if (!this.queueItem?.appointmentId) {
      return this.translate.instant('RECEPTION.NO_APPOINTMENT_SELECTED');
    }
    if (!this.identityConfirmed()) {
      return this.translate.instant('RECEPTION.CONFIRM_IDENTITY_FIRST');
    }
    const referenceMissing =
      (this.copayAmount() ?? 0) > 0 && this.copayNeedsReference() && !this.copayReference().trim();
    return referenceMissing ? this.translate.instant('SUBMIT_GUARD.PAYMENT_REFERENCE') : null;
  }

  submit(): void {
    if (!this.queueItem?.appointmentId) {
      this.toast.error(this.translate.instant('RECEPTION.NO_APPOINTMENT_SELECTED'));
      return;
    }

    if (!this.identityConfirmed()) {
      this.toast.error(this.translate.instant('RECEPTION.CONFIRM_IDENTITY_FIRST'));
      return;
    }

    this.saving.set(true);

    const request: CheckInRequest = {
      appointmentId: this.queueItem.appointmentId,
      chiefComplaint: this.chiefComplaint() || null,
      copayAmount: this.copayAmount(),
      ...((this.copayAmount() ?? 0) > 0
        ? {
            copayMethod: this.copayMethod(),
            copayReference: this.copayReference().trim() || null,
          }
        : {}),
      identityConfirmed: this.identityConfirmed(),
      insuranceVerified: this.insuranceVerified(),
      notes: this.notes() || null,
    };
    if (this.consentObtained()) {
      request.consentObtained = true;
      request.consentMethod = 'ELECTRONIC';
      request.consentSignedName = this.consentSignedName().trim() || null;
    }

    this.receptionService.checkInPatient(request).subscribe({
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
        const msg = refusalReason(err) ?? this.translate.instant('RECEPTION.CHECK_IN_FAILED');
        this.toast.error(msg);
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
      error: () => this.toast.error(this.translate.instant('RECEPTION.RECEIPT_FAILED')),
    });
  }
}
