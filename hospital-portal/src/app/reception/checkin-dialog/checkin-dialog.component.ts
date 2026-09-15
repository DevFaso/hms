import {
  Component,
  Output,
  EventEmitter,
  Input,
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

@Component({
  selector: 'app-checkin-dialog',
  standalone: true,
  imports: [FormsModule, TranslateModule],
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

  /* ── Form fields ────────────────────────── */
  chiefComplaint = signal('');
  copayAmount = signal<number | null>(null);
  identityConfirmed = signal(false);
  insuranceVerified = signal(false);
  /* Consent-to-treat (P3 #21): recorded, never gating — the submit does not
     require it, unlike identity confirmation. */
  consentObtained = signal(false);
  consentSignedName = signal('');
  notes = signal('');
  saving = signal(false);

  get canSubmit(): boolean {
    return !!this.queueItem?.appointmentId && this.identityConfirmed() && !this.saving();
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
        this.toast.success(
          response.message || this.translate.instant('RECEPTION.CHECK_IN_SUCCESS'),
        );
        this.checkedIn.emit(response);
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err?.error?.message ?? this.translate.instant('RECEPTION.CHECK_IN_FAILED');
        this.toast.error(msg);
      },
    });
  }
}
