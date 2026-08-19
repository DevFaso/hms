import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  BirthPlanService,
  BirthPlanRequest,
  BirthPlanResponse,
} from '../services/birth-plan.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

type BirthPlanFilter = 'all' | 'pending-review' | 'reviewed';

@Component({
  selector: 'app-birth-plan-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './birth-plan-tab.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BirthPlanTabComponent implements OnInit {
  private readonly birthPlanService = inject(BirthPlanService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  private hospitalId: string | null = null;

  /** Create/update includes NURSE on the backend. */
  readonly canEdit = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  /** Review = DOCTOR/MIDWIFE/SUPER_ADMIN. */
  readonly canReview = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);
  /** Delete + pending-review worklist exclude NURSE. */
  readonly canDelete = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canSeePendingReview = this.canDelete;

  filter = signal<BirthPlanFilter>('all');
  plans = signal<BirthPlanResponse[]>([]);
  loading = signal(false);
  page = signal(0);
  totalPages = signal(0);
  filterPatient = signal<PatientResponse | null>(null);

  showPlanModal = signal(false);
  editingPlanId = signal<string | null>(null);
  planSaving = signal(false);
  planForm: BirthPlanRequest = this.emptyPlanForm();
  planPatient = signal<PatientResponse | null>(null);
  /** Comma-separated inputs backing the List<String> fields. */
  listInputs = this.emptyListInputs();

  viewedPlan = signal<BirthPlanResponse | null>(null);

  showReviewModal = signal(false);
  reviewTarget = signal<BirthPlanResponse | null>(null);
  reviewSignature = '';
  reviewComments = '';
  reviewBusy = signal(false);

  showDeleteConfirm = signal(false);
  deleteTarget = signal<BirthPlanResponse | null>(null);
  deleteBusy = signal(false);

  ngOnInit(): void {
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
    this.load();
  }

  setFilter(filter: BirthPlanFilter): void {
    this.filter.set(filter);
    this.page.set(0);
    this.load();
  }

  onFilterPatientPicked(p: PatientResponse | null): void {
    this.filterPatient.set(p);
    this.page.set(0);
    this.load();
  }

  prev(): void {
    if (this.page() > 0) {
      this.page.update((p) => p - 1);
      this.load();
    }
  }

  next(): void {
    if (this.page() + 1 < this.totalPages()) {
      this.page.update((p) => p + 1);
      this.load();
    }
  }

  load(): void {
    const filter = this.filter();
    if (filter === 'pending-review' && !this.canSeePendingReview) return;
    this.loading.set(true);
    // The dedicated pending-review endpoint takes no patientId, so with a
    // patient selected it would silently list every patient's pending plans
    // under a patient-filtered UI. Use the search endpoint (which supports
    // both filters) whenever a patient is picked.
    const patientId = this.filterPatient()?.id;
    const source =
      filter === 'pending-review' && !patientId
        ? this.birthPlanService.pendingReview(this.hospitalId ?? undefined, this.page())
        : this.birthPlanService.search({
            hospitalId: this.hospitalId ?? undefined,
            patientId,
            providerReviewed:
              filter === 'reviewed' ? true : filter === 'pending-review' ? false : undefined,
            page: this.page(),
            size: 20,
          });
    source.subscribe({
      next: (result) => {
        this.plans.set(result?.content ?? []);
        this.totalPages.set(result?.totalPages ?? 0);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('BIRTH_PLAN.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  /* ── Create / edit ── */

  emptyPlanForm(): BirthPlanRequest {
    return {
      patientId: '',
      hospitalId: '',
      introduction: {},
      deliveryPreferences: {},
      painManagement: {},
      deliveryRoomEnvironment: {},
      postpartumPreferences: {},
      additionalWishes: '',
      flexibilityAcknowledgment: false,
      discussedWithProvider: false,
    };
  }

  emptyListInputs(): {
    unmedicated: string;
    medicated: string;
    supportPersons: string;
    comfortItems: string;
  } {
    return { unmedicated: '', medicated: '', supportPersons: '', comfortItems: '' };
  }

  openCreatePlan(): void {
    this.planForm = this.emptyPlanForm();
    this.listInputs = this.emptyListInputs();
    this.editingPlanId.set(null);
    this.planPatient.set(null);
    this.showPlanModal.set(true);
  }

  openEditPlan(plan: BirthPlanResponse): void {
    this.planForm = {
      patientId: plan.patientId,
      hospitalId: plan.hospitalId,
      introduction: { ...(plan.introduction ?? {}) },
      deliveryPreferences: { ...(plan.deliveryPreferences ?? {}) },
      painManagement: { ...(plan.painManagement ?? {}) },
      deliveryRoomEnvironment: { ...(plan.deliveryRoomEnvironment ?? {}) },
      postpartumPreferences: { ...(plan.postpartumPreferences ?? {}) },
      additionalWishes: plan.additionalWishes ?? '',
      flexibilityAcknowledgment: plan.flexibilityAcknowledgment ?? false,
      discussedWithProvider: plan.discussedWithProvider ?? false,
    };
    this.listInputs = {
      unmedicated: (plan.painManagement?.unmedicatedTechniques ?? []).join(', '),
      medicated: (plan.painManagement?.medicatedOptions ?? []).join(', '),
      supportPersons: (plan.deliveryRoomEnvironment?.supportPersons ?? []).join(', '),
      comfortItems: (plan.deliveryRoomEnvironment?.comfortItems ?? []).join(', '),
    };
    this.editingPlanId.set(plan.id);
    this.planPatient.set(
      plan.patientId
        ? ({
            id: plan.patientId,
            firstName: plan.introduction?.patientName ?? '',
            lastName: '',
            email: '',
          } as PatientResponse)
        : null,
    );
    this.showPlanModal.set(true);
  }

  closePlanModal(): void {
    this.showPlanModal.set(false);
  }

  onPlanPatientPicked(p: PatientResponse | null): void {
    this.planPatient.set(p);
    this.planForm.patientId = p?.id ?? '';
    if (p && !this.planForm.introduction.patientName) {
      this.planForm.introduction.patientName = `${p.firstName ?? ''} ${p.lastName ?? ''}`.trim();
    }
  }

  private splitList(value: string): string[] | undefined {
    const items = value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    return items.length ? items : undefined;
  }

  submitPlan(): void {
    const form = this.planForm;
    if (
      !form.patientId ||
      !form.introduction.patientName?.trim() ||
      !form.introduction.expectedDueDate ||
      !form.flexibilityAcknowledgment
    ) {
      this.toast.error(this.translate.instant('BIRTH_PLAN.REQUIRED_FIELDS'));
      return;
    }
    form.hospitalId = this.hospitalId ?? undefined;
    form.painManagement!.unmedicatedTechniques = this.splitList(this.listInputs.unmedicated);
    form.painManagement!.medicatedOptions = this.splitList(this.listInputs.medicated);
    form.deliveryRoomEnvironment!.supportPersons = this.splitList(this.listInputs.supportPersons);
    form.deliveryRoomEnvironment!.comfortItems = this.splitList(this.listInputs.comfortItems);
    this.planSaving.set(true);
    const editingId = this.editingPlanId();
    const op = editingId
      ? this.birthPlanService.update(editingId, form)
      : this.birthPlanService.create(form);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(editingId ? 'BIRTH_PLAN.UPDATED' : 'BIRTH_PLAN.CREATED'),
        );
        this.planSaving.set(false);
        this.closePlanModal();
        this.load();
      },
      error: () => {
        this.toast.error(this.translate.instant('BIRTH_PLAN.SAVE_ERROR'));
        this.planSaving.set(false);
      },
    });
  }

  /* ── Detail / review / delete ── */

  openPlan(plan: BirthPlanResponse): void {
    this.viewedPlan.set(plan);
  }

  closePlan(): void {
    this.viewedPlan.set(null);
  }

  openReview(plan: BirthPlanResponse): void {
    this.reviewTarget.set(plan);
    this.reviewSignature = '';
    this.reviewComments = '';
    this.showReviewModal.set(true);
  }

  closeReview(): void {
    this.showReviewModal.set(false);
    this.reviewTarget.set(null);
  }

  submitReview(): void {
    const plan = this.reviewTarget();
    if (!plan || !this.reviewSignature.trim()) {
      this.toast.error(this.translate.instant('BIRTH_PLAN.SIGNATURE_REQUIRED'));
      return;
    }
    this.reviewBusy.set(true);
    this.birthPlanService
      .review(plan.id, true, this.reviewSignature.trim(), this.reviewComments.trim() || undefined)
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('BIRTH_PLAN.REVIEWED_SUCCESS'));
          this.reviewBusy.set(false);
          this.closeReview();
          this.load();
        },
        error: () => {
          this.toast.error(this.translate.instant('BIRTH_PLAN.REVIEW_ERROR'));
          this.reviewBusy.set(false);
        },
      });
  }

  confirmDelete(plan: BirthPlanResponse): void {
    this.deleteTarget.set(plan);
    this.showDeleteConfirm.set(true);
  }

  closeDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deleteTarget.set(null);
  }

  submitDelete(): void {
    const plan = this.deleteTarget();
    if (!plan) return;
    this.deleteBusy.set(true);
    this.birthPlanService.delete(plan.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('BIRTH_PLAN.DELETED'));
        this.deleteBusy.set(false);
        this.closeDelete();
        this.load();
      },
      error: () => {
        this.toast.error(this.translate.instant('BIRTH_PLAN.DELETE_ERROR'));
        this.deleteBusy.set(false);
      },
    });
  }
}
