import { Component, Output, EventEmitter, Input, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
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
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './checkin-dialog.component.html',
  styleUrl: './checkin-dialog.component.scss',
})
export class CheckinDialogComponent {
  @Input() queueItem: ReceptionQueueItem | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() checkedIn = new EventEmitter<CheckInResponse>();

  private readonly receptionService = inject(ReceptionService);
  private readonly toast = inject(ToastService);

  /* ── Form fields ────────────────────────── */
  chiefComplaint = signal('');
  copayAmount = signal<number | null>(null);
  identityConfirmed = signal(false);
  insuranceVerified = signal(false);
  notes = signal('');
  saving = signal(false);

  get canSubmit(): boolean {
    return !!this.queueItem?.appointmentId && this.identityConfirmed() && !this.saving();
  }

  submit(): void {
    if (!this.queueItem?.appointmentId) {
      this.toast.error('No appointment selected for check-in');
      return;
    }

    if (!this.identityConfirmed()) {
      this.toast.error('Please confirm patient identity before check-in');
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

    this.receptionService.checkInPatient(request).subscribe({
      next: (response) => {
        this.saving.set(false);
        this.toast.success(response.message || 'Patient checked in successfully');
        this.checkedIn.emit(response);
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err?.error?.message ?? 'Failed to check in patient. Please try again.';
        this.toast.error(msg);
      },
    });
  }
}
