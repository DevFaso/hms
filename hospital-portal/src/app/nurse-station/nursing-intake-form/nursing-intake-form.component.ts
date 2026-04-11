import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  EncounterService,
  EncounterResponse,
  NursingIntakeRequest,
  NursingIntakeResponse,
  AllergyEntry,
  MedicationReconciliationEntry,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-nursing-intake-form',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './nursing-intake-form.component.html',
  styleUrl: './nursing-intake-form.component.scss',
})
export class NursingIntakeFormComponent {
  @Input() encounter: EncounterResponse | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() intakeCompleted = new EventEmitter<NursingIntakeResponse>();

  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);

  /* ── Allergy reconciliation ─────────────── */
  allergies = signal<AllergyEntry[]>([]);

  /* ── Medication reconciliation ──────────── */
  medications = signal<MedicationReconciliationEntry[]>([]);

  /* ── Nursing assessment ─────────────────── */
  nursingAssessmentNotes = signal('');
  chiefComplaint = signal('');
  painAssessment = signal('');
  fallRiskDetail = signal('');

  /* ── UI state ───────────────────────────── */
  saving = signal(false);

  get canSubmit(): boolean {
    return !!this.encounter?.id && !this.saving();
  }

  trackByIndex(index: number): number {
    return index;
  }

  addAllergy(): void {
    this.allergies.update((list) => [
      ...list,
      { allergenDisplay: '', category: '', severity: '', reaction: '' },
    ]);
  }

  removeAllergy(index: number): void {
    this.allergies.update((list) => list.filter((_, i) => i !== index));
  }

  addMedication(): void {
    this.medications.update((list) => [
      ...list,
      { medicationName: '', dosage: '', frequency: '', route: '', stillTaking: true, notes: '' },
    ]);
  }

  removeMedication(index: number): void {
    this.medications.update((list) => list.filter((_, i) => i !== index));
  }

  submit(): void {
    if (!this.encounter?.id) {
      this.toast.error('No encounter selected for nursing intake');
      return;
    }

    this.saving.set(true);

    const request: NursingIntakeRequest = {
      allergies:
        this.allergies().length > 0
          ? this.allergies().filter((a) => a.allergenDisplay?.trim())
          : undefined,
      medications:
        this.medications().length > 0
          ? this.medications().filter((m) => m.medicationName?.trim())
          : undefined,
      nursingAssessmentNotes: this.nursingAssessmentNotes() || undefined,
      chiefComplaint: this.chiefComplaint() || undefined,
      painAssessment: this.painAssessment() || undefined,
      fallRiskDetail: this.fallRiskDetail() || undefined,
    };

    this.encounterService.submitNursingIntake(this.encounter.id, request).subscribe({
      next: (response) => {
        this.saving.set(false);
        this.toast.success('Nursing intake completed successfully');
        this.intakeCompleted.emit(response);
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err?.error?.message ?? 'Failed to submit nursing intake. Please try again.';
        this.toast.error(msg);
      },
    });
  }
}
