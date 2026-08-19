import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  MedicationTimelineService,
  DrugInteraction,
  InteractionSeverity,
  MedicationTimelineEntry,
  MedicationTimelineResponse,
  PharmacyFillRequest,
  PharmacyFillResponse,
} from '../services/medication-timeline.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

/**
 * Medication-history workspace (Phase 3 task 15): the /medication-history
 * timeline (prescriptions + external pharmacy fills with overlap/interaction
 * detection) and pharmacy-fill recording. Route roles mirror the backend
 * timeline gate exactly: DOCTOR/NURSE/PHARMACIST/LAB_SCIENTIST.
 */
@Component({
  selector: 'app-medication-history',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './medication-history.html',
  styleUrl: './medication-history.scss',
})
export class MedicationHistoryComponent implements OnInit {
  private readonly timelineService = inject(MedicationTimelineService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  private hospitalId: string | null = null;

  /** Fill create/update = DOCTOR/PHARMACIST on the backend. */
  readonly canRecordFill = this.roleContext.hasAnyActiveRole(['ROLE_DOCTOR', 'ROLE_PHARMACIST']);
  /** Fill reads exclude LAB_SCIENTIST (timeline-only role). */
  readonly canSeeFills = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_PHARMACIST',
  ]);

  patient = signal<PatientResponse | null>(null);
  startDate = '';
  endDate = '';

  timeline = signal<MedicationTimelineResponse | null>(null);
  timelineLoading = signal(false);
  timelineError = signal(false);

  fills = signal<PharmacyFillResponse[]>([]);
  fillsLoading = signal(false);

  showFillModal = signal(false);
  editingFillId = signal<string | null>(null);
  fillSaving = signal(false);
  fillForm: PharmacyFillRequest = this.emptyFillForm();

  readonly severityOrder: InteractionSeverity[] = [
    'CONTRAINDICATED',
    'MAJOR',
    'MODERATE',
    'MINOR',
    'UNKNOWN',
  ];

  /** Interactions sorted most-severe first. */
  readonly sortedInteractions = computed<DrugInteraction[]>(() => {
    const interactions = this.timeline()?.detectedInteractions ?? [];
    return [...interactions].sort(
      (a, b) =>
        this.severityOrder.indexOf(a.severity ?? 'UNKNOWN') -
        this.severityOrder.indexOf(b.severity ?? 'UNKNOWN'),
    );
  });

  ngOnInit(): void {
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
  }

  private todayIso(): string {
    const d = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  onPatientPicked(p: PatientResponse | null): void {
    this.patient.set(p);
    this.timeline.set(null);
    this.timelineError.set(false);
    this.fills.set([]);
    if (p) {
      this.load();
    }
  }

  load(): void {
    const patient = this.patient();
    if (!patient || !this.hospitalId) return;
    const hospitalId = this.hospitalId;
    this.timelineLoading.set(true);
    this.timelineError.set(false);
    this.timelineService
      .timeline(patient.id, hospitalId, this.startDate || undefined, this.endDate || undefined)
      .subscribe({
        next: (timeline) => {
          this.timeline.set(timeline);
          this.timelineLoading.set(false);
        },
        error: () => {
          this.timeline.set(null);
          this.timelineError.set(true);
          this.timelineLoading.set(false);
          this.toast.error(this.translate.instant('MED_TIMELINE.LOAD_ERROR'));
        },
      });
    if (this.canSeeFills) {
      this.fillsLoading.set(true);
      this.timelineService.fillsForPatient(patient.id, hospitalId).subscribe({
        next: (fills) => {
          this.fills.set(fills ?? []);
          this.fillsLoading.set(false);
        },
        error: () => {
          this.fills.set([]);
          this.fillsLoading.set(false);
          this.toast.error(this.translate.instant('MED_TIMELINE.FILLS_LOAD_ERROR'));
        },
      });
    }
  }

  severityClass(severity: InteractionSeverity | undefined): string {
    switch (severity) {
      case 'CONTRAINDICATED':
      case 'MAJOR':
        return 'sev-badge sev-high';
      case 'MODERATE':
        return 'sev-badge sev-mid';
      case 'MINOR':
        return 'sev-badge sev-low';
      default:
        return 'sev-badge';
    }
  }

  entryTypeClass(entry: MedicationTimelineEntry): string {
    return entry.entryType === 'PHARMACY_FILL' ? 'type-badge type-fill' : 'type-badge type-rx';
  }

  /* ── Fill recording ── */

  emptyFillForm(): PharmacyFillRequest {
    return {
      patientId: '',
      hospitalId: '',
      medicationName: '',
      fillDate: this.todayIso(),
      refillNumber: 0,
    };
  }

  openRecordFill(): void {
    const patient = this.patient();
    if (!patient) return;
    this.fillForm = {
      ...this.emptyFillForm(),
      patientId: patient.id,
      hospitalId: this.hospitalId ?? '',
    };
    this.editingFillId.set(null);
    this.showFillModal.set(true);
  }

  openEditFill(fill: PharmacyFillResponse): void {
    // PUT is a full replace — round-trip the complete record.
    this.fillForm = {
      ...fill,
      patientId: fill.patientId,
      hospitalId: fill.hospitalId,
      medicationName: fill.medicationName ?? '',
      fillDate: fill.fillDate ?? this.todayIso(),
    };
    this.editingFillId.set(fill.id);
    this.showFillModal.set(true);
  }

  closeFillModal(): void {
    this.showFillModal.set(false);
  }

  submitFill(): void {
    const form = this.fillForm;
    if (!form.medicationName.trim() || !form.fillDate) {
      this.toast.error(this.translate.instant('MED_TIMELINE.FILL_REQUIRED_FIELDS'));
      return;
    }
    this.fillSaving.set(true);
    const editingId = this.editingFillId();
    const op = editingId
      ? this.timelineService.updateFill(editingId, form)
      : this.timelineService.createFill(form);
    op.subscribe({
      next: (saved) => {
        this.toast.success(
          this.translate.instant(
            editingId ? 'MED_TIMELINE.FILL_UPDATED' : 'MED_TIMELINE.FILL_CREATED',
          ),
        );
        this.fillSaving.set(false);
        this.closeFillModal();
        // Patch the fills list in place; the timeline is server-computed
        // (overlaps/interactions), so refetch only that.
        this.fills.update((list) =>
          editingId ? list.map((f) => (f.id === saved.id ? saved : f)) : [saved, ...list],
        );
        this.reloadTimelineOnly();
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_TIMELINE.FILL_SAVE_ERROR'));
        this.fillSaving.set(false);
      },
    });
  }

  private reloadTimelineOnly(): void {
    const patient = this.patient();
    if (!patient || !this.hospitalId) return;
    this.timelineLoading.set(true);
    this.timelineService
      .timeline(patient.id, this.hospitalId, this.startDate || undefined, this.endDate || undefined)
      .subscribe({
        next: (timeline) => {
          this.timeline.set(timeline);
          this.timelineLoading.set(false);
        },
        error: () => {
          this.timelineError.set(true);
          this.timelineLoading.set(false);
        },
      });
  }
}
