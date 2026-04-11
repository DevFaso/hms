import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  EncounterService,
  EncounterResponse,
  CheckOutRequest,
  AfterVisitSummary,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-checkout-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './checkout-dialog.component.html',
  styleUrl: './checkout-dialog.component.scss',
})
export class CheckoutDialogComponent {
  @Input() encounter: EncounterResponse | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() checkoutCompleted = new EventEmitter<AfterVisitSummary>();

  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── Form fields ──────────────────────── */
  followUpInstructions = signal('');
  dischargeDiagnosesText = signal('');
  prescriptionSummary = signal('');
  referralSummary = signal('');
  patientEducationMaterials = signal('');
  followUpReason = signal('');
  followUpDate = signal('');

  /* ── UI state ─────────────────────────── */
  saving = signal(false);
  avs = signal<AfterVisitSummary | null>(null);

  get canSubmit(): boolean {
    return !!this.encounter && !this.saving();
  }

  submit(): void {
    if (!this.encounter || this.saving()) return;

    this.saving.set(true);

    const diagnoses = this.dischargeDiagnosesText()
      .split('\n')
      .map((d) => d.trim())
      .filter((d) => d.length > 0);

    const request: CheckOutRequest = {
      followUpInstructions: this.followUpInstructions() || undefined,
      dischargeDiagnoses: diagnoses.length ? diagnoses : undefined,
      prescriptionSummary: this.prescriptionSummary() || undefined,
      referralSummary: this.referralSummary() || undefined,
      patientEducationMaterials: this.patientEducationMaterials() || undefined,
    };

    if (this.followUpReason()) {
      request.followUpAppointment = {
        reason: this.followUpReason(),
        preferredDate: this.followUpDate() || undefined,
      };
    }

    this.encounterService.checkOut(this.encounter.id, request).subscribe({
      next: (summary) => {
        this.avs.set(summary);
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('checkout.success') || 'Patient checked out successfully',
        );
        this.checkoutCompleted.emit(summary);
      },
      error: () => {
        this.saving.set(false);
        this.toast.error(
          this.translate.instant('checkout.error') || 'Check-out failed. Please try again.',
        );
      },
    });
  }

  close(): void {
    this.dismissed.emit();
  }
}
