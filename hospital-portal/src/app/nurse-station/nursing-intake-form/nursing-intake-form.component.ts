import {
  Component,
  EventEmitter,
  Input,
  Output,
  inject,
  signal,
  ChangeDetectionStrategy,
  DestroyRef,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  EncounterService,
  EncounterResponse,
  NursingIntakeRequest,
  NursingIntakeResponse,
  AllergyEntry,
  MedicationReconciliationEntry,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { PatientAllergy, PatientService } from '../../services/patient.service';
import { PrescriptionResponse, PrescriptionService } from '../../services/prescription.service';

@Component({
  selector: 'app-nursing-intake-form',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, ModalDialogDirective],
  templateUrl: './nursing-intake-form.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './nursing-intake-form.component.scss',
})
export class NursingIntakeFormComponent {
  /**
   * QA D951: opening the intake carries in what the chart already knows: the
   * chief complaint given at check-in or triage, the documented allergies and
   * the medicines prescribed here, so the nurse reconciles instead of retyping.
   */
  @Input() set encounter(value: EncounterResponse | null) {
    const changed = value?.id !== this._encounter?.id;
    this._encounter = value;
    if (value?.chiefComplaint && !this.chiefComplaint()) {
      this.chiefComplaint.set(value.chiefComplaint);
    }
    if (changed && value?.patientId) {
      this.loadDocumented(value.patientId, value.hospitalId);
    }
  }
  get encounter(): EncounterResponse | null {
    return this._encounter;
  }
  private _encounter: EncounterResponse | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() intakeCompleted = new EventEmitter<NursingIntakeResponse>();

  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly patientService = inject(PatientService);
  private readonly prescriptionService = inject(PrescriptionService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Already on the chart. Shown read-only: the intake endpoint creates an
   * allergy row for every entry it receives, so re-sending a documented one
   * would duplicate it. Only allergies the nurse adds below are sent.
   */
  documentedAllergies = signal<PatientAllergy[]>([]);
  /** Prescribed here and still current; the nurse may copy one into the reconciliation. */
  documentedMedications = signal<PrescriptionResponse[]>([]);

  /** Prescription states that mean the medicine is still meant to be taken. */
  private static readonly CURRENT_PRESCRIPTION_STATUSES = [
    'SIGNED',
    'TRANSMITTED',
    'DISPENSED',
    'PARTIALLY_FILLED',
    'PENDING_STOCK',
    'SENT_TO_PARTNER',
    'PARTNER_ACCEPTED',
    'PARTNER_DISPENSED',
    'PRINTED_FOR_PATIENT',
  ];

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

  /** Review of #971: Escape closes the intake only while nothing has been typed. */
  onEscape(): void {
    if (!this.hasEntries()) this.dismissed.emit();
  }

  hasEntries(): boolean {
    return (
      this.allergies().length > 0 ||
      this.medications().length > 0 ||
      !!this.nursingAssessmentNotes().trim() ||
      !!this.painAssessment().trim() ||
      !!this.fallRiskDetail().trim() ||
      this.chiefComplaint().trim() !== (this.encounter?.chiefComplaint ?? '').trim()
    );
  }

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

  /** Copy a prescribed medicine into the reconciliation list, once. */
  reconcilePrescription(rx: PrescriptionResponse): void {
    const name = rx.medicationDisplayName || rx.medicationName;
    if (!name) return;
    const already = this.medications().some(
      (m) => (m.medicationName ?? '').trim().toLowerCase() === name.trim().toLowerCase(),
    );
    if (already) return;
    this.medications.update((list) => [
      ...list,
      {
        medicationName: name,
        dosage: rx.dosage ?? '',
        frequency: rx.frequency ?? '',
        route: rx.route ?? '',
        stillTaking: true,
        notes: '',
      },
    ]);
  }

  isReconciled(rx: PrescriptionResponse): boolean {
    const name = (rx.medicationDisplayName || rx.medicationName || '').trim().toLowerCase();
    return this.medications().some((m) => (m.medicationName ?? '').trim().toLowerCase() === name);
  }

  private loadDocumented(patientId: string, hospitalId?: string): void {
    this.documentedAllergies.set([]);
    this.documentedMedications.set([]);
    this.patientService
      .listAllergies(patientId, hospitalId || undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => this.documentedAllergies.set(res.items.filter((a) => a.active !== false)),
        error: () => this.documentedAllergies.set([]),
      });
    this.prescriptionService
      .list({
        patientId,
        statuses: NursingIntakeFormComponent.CURRENT_PRESCRIPTION_STATUSES,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.documentedMedications.set(list),
        error: () => this.documentedMedications.set([]),
      });
  }

  removeMedication(index: number): void {
    this.medications.update((list) => list.filter((_, i) => i !== index));
  }

  submit(): void {
    if (!this.encounter?.id) {
      this.toast.error(this.translate.instant('INTAKE.NO_ENCOUNTER'));
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
        this.toast.success(this.translate.instant('INTAKE.COMPLETED'));
        this.intakeCompleted.emit(response);
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err?.error?.message ?? this.translate.instant('INTAKE.SUBMIT_FAILED');
        this.toast.error(msg);
      },
    });
  }
}
