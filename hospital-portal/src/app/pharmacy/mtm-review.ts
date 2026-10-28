import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { PharmacyService, MtmReviewRequest, MtmReviewResponse } from '../services/pharmacy.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { PatientResponse } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { finalize } from 'rxjs';
import { localDateString } from '../shared/date-utils';
import { refusalReason } from '../shared/refusal-reason';

/**
 * P-09: MTM (Medication Therapy Management) review screen — pharmacist-led
 * chronic disease review, adherence counselling flag, polypharmacy alert,
 * intervention record. This is a foundation skeleton; the workflow integrates
 * with the existing retrospective timeline produced by MedicationHistoryService.
 */
@Component({
  selector: 'app-mtm-review',
  standalone: true,
  imports: [
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    PatientPickerComponent,
  ],
  templateUrl: './mtm-review.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './mtm-review.scss',
})
export class MtmReviewComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly svc = inject(PharmacyService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  reviews = signal<MtmReviewResponse[]>([]);
  loading = signal(false);
  saving = signal(false);

  // Form state
  showForm = signal(false);
  selectedReviewId: string | null = null;
  /** QA D886: the status of the review being edited, to offer only the moves the server allows. */
  editingStatus: string | null = null;
  form: MtmReviewRequest = this.emptyForm();
  /**
   * QA D900: the patient is picked by name or MRN at the pinned hospital, not
   * typed as a UUID. A review's patient is fixed once started (the server
   * ignores a changed one), so an edit shows who it is for instead.
   */
  readonly pickedPatient = signal<PatientResponse | null>(null);
  editingPatientLabel: string | null = null;
  /** QA D886: a follow-up is in the future; the picker stops before today. */
  minFollowUp = localDateString();

  ngOnInit(): void {
    this.loadReviews();
  }

  loadReviews(): void {
    // The route gate renders this page only with a hospital pinned; the null
    // check narrows the type and never falls back to another hospital.
    const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
    if (hospitalId == null) {
      this.reviews.set([]);
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.svc
      .listMtmReviewsByHospital(hospitalId, 0, 50)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (page) => {
          this.reviews.set(page?.content ?? []);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.toast.error(this.translate.instant('PHARMACY.MTM_LOAD_FAILED'));
        },
      });
  }

  openCreate(): void {
    this.selectedReviewId = null;
    this.editingStatus = null;
    this.minFollowUp = localDateString();
    this.form = this.emptyForm();
    this.form.hospitalId = this.roleContext.effectiveHospitalIdForRequest() ?? '';
    this.pickedPatient.set(null);
    this.editingPatientLabel = null;
    this.showForm.set(true);
  }

  onPatientPicked(patient: PatientResponse | null): void {
    this.pickedPatient.set(patient);
    this.form.patientId = patient?.id ?? '';
  }

  /** QA D900: "Name · MRN" for the list and the edit form; never the UUID. */
  patientLabel(review: MtmReviewResponse): string {
    const name = review.patientName?.trim();
    const mrn = review.patientMrn?.trim();
    if (name && mrn) return `${name} · ${mrn}`;
    return name || mrn || '—';
  }

  /**
   * QA D886: a COMPLETED review is closed (the server refuses any change), so
   * it is not offered for editing; a REFERRED one may only stay referred or be
   * completed.
   */
  canEdit(review: MtmReviewResponse): boolean {
    return review.status !== 'COMPLETED';
  }

  canChooseDraft(): boolean {
    return this.editingStatus !== 'REFERRED';
  }

  openEdit(review: MtmReviewResponse): void {
    if (!this.canEdit(review)) return;
    this.selectedReviewId = review.id;
    this.editingStatus = review.status;
    this.pickedPatient.set(null);
    this.editingPatientLabel = this.patientLabel(review);
    // A kept follow-up date that has since passed stays valid (the server does not re-judge it).
    const today = localDateString();
    this.minFollowUp =
      review.followUpDate && review.followUpDate < today ? review.followUpDate : today;
    this.form = {
      patientId: review.patientId,
      hospitalId: review.hospitalId,
      chronicConditionFocus: review.chronicConditionFocus,
      adherenceConcern: review.adherenceConcern,
      interventionSummary: review.interventionSummary,
      recommendedActions: review.recommendedActions,
      status: review.status,
      followUpDate: review.followUpDate,
    };
    this.showForm.set(true);
  }

  closeForm(): void {
    this.showForm.set(false);
    this.selectedReviewId = null;
  }

  submit(): void {
    if (!this.form.patientId || !this.form.hospitalId) {
      this.toast.error(this.translate.instant('PHARMACY.MTM_PATIENT_HOSPITAL_REQUIRED'));
      return;
    }
    this.saving.set(true);
    const stream = this.selectedReviewId
      ? this.svc.updateMtmReview(this.selectedReviewId, this.form)
      : this.svc.startMtmReview(this.form);
    stream.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('PHARMACY.MTM_SAVED'));
        this.saving.set(false);
        this.closeForm();
        this.loadReviews();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(refusalReason(err) ?? this.translate.instant('PHARMACY.MTM_SAVE_FAILED'));
      },
    });
  }

  statusBadgeClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'badge-success';
      case 'REFERRED':
        return 'badge-warning';
      default:
        return 'badge-info';
    }
  }

  private emptyForm(): MtmReviewRequest {
    return {
      patientId: '',
      hospitalId: '',
      chronicConditionFocus: '',
      adherenceConcern: false,
      interventionSummary: '',
      recommendedActions: '',
      status: 'DRAFT',
      followUpDate: '',
    };
  }
}
