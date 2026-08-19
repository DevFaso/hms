import { Component, Input, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PatientService,
  PatientAllergy,
  PatientAllergyRequest,
  AllergySeverity,
  AllergyVerificationStatus,
  PatientProblem,
  PatientDiagnosisRequest,
  ProblemStatus,
  ProblemSeverity,
  ChartUpdate,
  ChartUpdateRequest,
  ChartSectionType,
  PatientTimeline,
} from '../../services/patient.service';
import { AuthService } from '../../auth/auth.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';

type ChartSection = 'allergies' | 'problems' | 'updates' | 'timeline';

@Component({
  selector: 'app-patient-chart',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './patient-chart.component.html',
  styleUrl: './patient-chart.component.scss',
})
export class PatientChartComponent implements OnInit {
  @Input({ required: true }) patientId = '';

  private readonly patientService = inject(PatientService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  section = signal<ChartSection>('allergies');

  /* ── Role gates (mirror backend @PreAuthorize) ── */
  readonly canViewAllergies = this.hasAnyRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_PHARMACIST',
  ]);
  readonly canEditAllergies = this.hasAnyRole(['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST']);
  readonly canViewProblems = this.hasAnyRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_MIDWIFE',
  ]);
  readonly canEditProblems = this.hasAnyRole(['ROLE_DOCTOR']);
  readonly canViewUpdates = this.hasAnyRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_HOSPITAL_ADMIN',
  ]);
  readonly canCreateUpdates = this.hasAnyRole(['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE']);
  readonly canViewTimeline = this.hasAnyRole(['ROLE_DOCTOR']);

  /* ── Allergies ── */
  allergies = signal<PatientAllergy[]>([]);
  allergiesLoading = signal(false);
  showAllergyModal = signal(false);
  editingAllergyId = signal<string | null>(null);
  allergySaving = signal(false);
  allergyForm: PatientAllergyRequest = this.emptyAllergyForm();
  showAllergyDeactivate = signal(false);
  deactivatingAllergy = signal<PatientAllergy | null>(null);
  deactivateReason = '';

  readonly severities: AllergySeverity[] = [
    'MILD',
    'MODERATE',
    'SEVERE',
    'LIFE_THREATENING',
    'UNKNOWN',
  ];
  readonly verificationStatuses: AllergyVerificationStatus[] = [
    'UNCONFIRMED',
    'PROVISIONAL',
    'CONFIRMED',
    'REFUTED',
    'ENTERED_IN_ERROR',
  ];

  /* ── Problems ── */
  problems = signal<PatientProblem[]>([]);
  problemsLoading = signal(false);
  includeHistorical = signal(false);
  showProblemModal = signal(false);
  editingProblemId = signal<string | null>(null);
  problemSaving = signal(false);
  problemForm: PatientDiagnosisRequest = this.emptyProblemForm();
  showProblemDelete = signal(false);
  deletingProblem = signal<PatientProblem | null>(null);
  problemDeleteReason = '';

  readonly problemStatuses: ProblemStatus[] = ['ACTIVE', 'RESOLVED', 'INACTIVE', 'RECURRENCE'];
  readonly problemSeverities: ProblemSeverity[] = [
    'UNKNOWN',
    'MILD',
    'MODERATE',
    'SEVERE',
    'LIFE_THREATENING',
  ];

  /* ── Chart updates ── */
  updates = signal<ChartUpdate[]>([]);
  updatesLoading = signal(false);
  expandedUpdateId = signal<string | null>(null);
  showUpdateModal = signal(false);
  updateSaving = signal(false);
  updateForm: ChartUpdateRequest = this.emptyUpdateForm();

  readonly sectionTypes: ChartSectionType[] = [
    'DIAGNOSIS',
    'PROBLEM',
    'ALLERGY',
    'MEDICAL_HISTORY',
    'SURGICAL_HISTORY',
    'SOCIAL_HISTORY',
    'FAMILY_HISTORY',
    'HOSPITALIZATION',
    'IMMUNIZATION',
    'CARE_PLAN',
    'MEDICATION',
    'NOTE',
    'OTHER',
  ];

  /* ── Timeline ── */
  timeline = signal<PatientTimeline | null>(null);
  timelineLoading = signal(false);
  showTimelineReason = signal(false);
  timelineReason = '';

  ngOnInit(): void {
    if (!this.canViewAllergies) {
      this.section.set(this.canViewProblems ? 'problems' : 'updates');
    }
    this.loadCurrentSection();
  }

  private hasAnyRole(roles: string[]): boolean {
    const active = this.roleContext.activeRole;
    if (active) return roles.includes(active);
    return this.auth.hasAnyRole(roles);
  }

  private hospitalId(): string {
    return this.roleContext.activeHospitalId ?? this.auth.getHospitalId() ?? '';
  }

  setSection(section: ChartSection): void {
    this.section.set(section);
    this.loadCurrentSection();
  }

  private loadCurrentSection(): void {
    switch (this.section()) {
      case 'allergies':
        if (this.canViewAllergies && this.allergies().length === 0) this.loadAllergies();
        break;
      case 'problems':
        if (this.canViewProblems && this.problems().length === 0) this.loadProblems();
        break;
      case 'updates':
        if (this.canViewUpdates && this.updates().length === 0) this.loadUpdates();
        break;
      case 'timeline':
        // Timeline requires an access reason first — prompt instead of loading.
        if (this.canViewTimeline && !this.timeline()) this.openTimelineReason();
        break;
    }
  }

  /* ── Allergies ── */

  loadAllergies(): void {
    this.allergiesLoading.set(true);
    this.patientService.listAllergies(this.patientId, this.hospitalId() || undefined).subscribe({
      next: (list) => {
        this.allergies.set(list ?? []);
        this.allergiesLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.ALLERGIES_LOAD_ERROR'));
        this.allergiesLoading.set(false);
      },
    });
  }

  emptyAllergyForm(): PatientAllergyRequest {
    return {
      allergenDisplay: '',
      category: '',
      severity: 'UNKNOWN',
      verificationStatus: 'UNCONFIRMED',
      reaction: '',
      reactionNotes: '',
      onsetDate: undefined,
      active: true,
    };
  }

  openAddAllergy(): void {
    this.allergyForm = this.emptyAllergyForm();
    this.editingAllergyId.set(null);
    this.showAllergyModal.set(true);
  }

  openEditAllergy(a: PatientAllergy): void {
    this.allergyForm = {
      allergenDisplay: a.allergenDisplay,
      allergenCode: a.allergenCode,
      category: a.category ?? '',
      severity: (a.severity as AllergySeverity) ?? 'UNKNOWN',
      verificationStatus: (a.verificationStatus as AllergyVerificationStatus) ?? 'UNCONFIRMED',
      reaction: a.reaction ?? '',
      reactionNotes: a.reactionNotes ?? '',
      onsetDate: a.onsetDate ?? undefined,
      active: a.active ?? true,
    };
    this.editingAllergyId.set(a.id);
    this.showAllergyModal.set(true);
  }

  closeAllergyModal(): void {
    this.showAllergyModal.set(false);
  }

  submitAllergy(): void {
    if (!this.allergyForm.allergenDisplay.trim()) {
      this.toast.error(this.translate.instant('CHART.ALLERGEN_REQUIRED'));
      return;
    }
    this.allergySaving.set(true);
    const req: PatientAllergyRequest = {
      ...this.allergyForm,
      hospitalId: this.hospitalId() || undefined,
      onsetDate: this.allergyForm.onsetDate || undefined,
    };
    const id = this.editingAllergyId();
    const op = id
      ? this.patientService.updateAllergy(this.patientId, id, req)
      : this.patientService.addAllergy(this.patientId, req);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(id ? 'CHART.ALLERGY_UPDATED' : 'CHART.ALLERGY_ADDED'),
        );
        this.allergySaving.set(false);
        this.closeAllergyModal();
        this.loadAllergies();
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.ALLERGY_SAVE_ERROR'));
        this.allergySaving.set(false);
      },
    });
  }

  openDeactivateAllergy(a: PatientAllergy): void {
    this.deactivatingAllergy.set(a);
    this.deactivateReason = '';
    this.showAllergyDeactivate.set(true);
  }

  closeDeactivateAllergy(): void {
    this.showAllergyDeactivate.set(false);
    this.deactivatingAllergy.set(null);
  }

  submitDeactivateAllergy(): void {
    const target = this.deactivatingAllergy();
    if (!target || !this.deactivateReason.trim()) {
      this.toast.error(this.translate.instant('CHART.REASON_REQUIRED'));
      return;
    }
    this.patientService
      .deactivateAllergy(this.patientId, target.id, this.deactivateReason.trim())
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('CHART.ALLERGY_DEACTIVATED'));
          this.closeDeactivateAllergy();
          this.loadAllergies();
        },
        error: () => this.toast.error(this.translate.instant('CHART.ALLERGY_SAVE_ERROR')),
      });
  }

  /* ── Problems ── */

  loadProblems(): void {
    this.problemsLoading.set(true);
    this.patientService
      .listDiagnoses(this.patientId, {
        hospitalId: this.hospitalId() || undefined,
        includeHistorical: this.includeHistorical(),
      })
      .subscribe({
        next: (list) => {
          this.problems.set(list ?? []);
          this.problemsLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('CHART.PROBLEMS_LOAD_ERROR'));
          this.problemsLoading.set(false);
        },
      });
  }

  toggleHistorical(): void {
    this.includeHistorical.update((v) => !v);
    this.loadProblems();
  }

  emptyProblemForm(): PatientDiagnosisRequest {
    return {
      hospitalId: '',
      problemDisplay: '',
      problemCode: '',
      icdVersion: 'ICD-10',
      status: 'ACTIVE',
      severity: 'UNKNOWN',
      onsetDate: undefined,
      notes: '',
      chronic: false,
    };
  }

  openAddProblem(): void {
    this.problemForm = this.emptyProblemForm();
    this.editingProblemId.set(null);
    this.showProblemModal.set(true);
  }

  openEditProblem(p: PatientProblem): void {
    this.problemForm = {
      hospitalId: p.hospitalId ?? '',
      problemDisplay: p.problemDisplay,
      problemCode: p.problemCode ?? '',
      icdVersion: p.icdVersion ?? 'ICD-10',
      status: (p.status as ProblemStatus) ?? 'ACTIVE',
      severity: (p.severity as ProblemSeverity) ?? 'UNKNOWN',
      onsetDate: p.onsetDate ?? undefined,
      notes: p.notes ?? '',
      chronic: p.chronic ?? false,
    };
    this.editingProblemId.set(p.id);
    this.showProblemModal.set(true);
  }

  closeProblemModal(): void {
    this.showProblemModal.set(false);
  }

  submitProblem(): void {
    if (!this.problemForm.problemDisplay.trim()) {
      this.toast.error(this.translate.instant('CHART.PROBLEM_REQUIRED'));
      return;
    }
    this.problemSaving.set(true);
    const req: PatientDiagnosisRequest = {
      ...this.problemForm,
      hospitalId: this.hospitalId(),
      onsetDate: this.problemForm.onsetDate || undefined,
    };
    const id = this.editingProblemId();
    const op = id
      ? this.patientService.updateDiagnosis(this.patientId, id, req)
      : this.patientService.addDiagnosis(this.patientId, req);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(id ? 'CHART.PROBLEM_UPDATED' : 'CHART.PROBLEM_ADDED'),
        );
        this.problemSaving.set(false);
        this.closeProblemModal();
        this.loadProblems();
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.PROBLEM_SAVE_ERROR'));
        this.problemSaving.set(false);
      },
    });
  }

  openDeleteProblem(p: PatientProblem): void {
    this.deletingProblem.set(p);
    this.problemDeleteReason = '';
    this.showProblemDelete.set(true);
  }

  closeDeleteProblem(): void {
    this.showProblemDelete.set(false);
    this.deletingProblem.set(null);
  }

  submitDeleteProblem(): void {
    const target = this.deletingProblem();
    if (!target || !this.problemDeleteReason.trim()) {
      this.toast.error(this.translate.instant('CHART.REASON_REQUIRED'));
      return;
    }
    this.patientService
      .deleteDiagnosis(this.patientId, target.id, this.problemDeleteReason.trim())
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('CHART.PROBLEM_DELETED'));
          this.closeDeleteProblem();
          this.loadProblems();
        },
        error: () => this.toast.error(this.translate.instant('CHART.PROBLEM_SAVE_ERROR')),
      });
  }

  /* ── Chart updates ── */

  loadUpdates(): void {
    this.updatesLoading.set(true);
    this.patientService
      .listChartUpdates(this.patientId, { hospitalId: this.hospitalId() || undefined, size: 20 })
      .subscribe({
        next: (page) => {
          this.updates.set(page?.content ?? []);
          this.updatesLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('CHART.UPDATES_LOAD_ERROR'));
          this.updatesLoading.set(false);
        },
      });
  }

  toggleUpdate(update: ChartUpdate): void {
    this.expandedUpdateId.update((current) => (current === update.id ? null : update.id));
  }

  emptyUpdateForm(): ChartUpdateRequest {
    return { hospitalId: '', updateReason: '', summary: '', notifyCareTeam: false, sections: [] };
  }

  openCreateUpdate(): void {
    this.updateForm = this.emptyUpdateForm();
    this.showUpdateModal.set(true);
  }

  closeUpdateModal(): void {
    this.showUpdateModal.set(false);
  }

  addUpdateSection(): void {
    this.updateForm.sections!.push({ sectionType: 'NOTE', display: '', narrative: '' });
  }

  removeUpdateSection(index: number): void {
    this.updateForm.sections!.splice(index, 1);
  }

  submitUpdate(): void {
    if (!this.updateForm.updateReason.trim()) {
      this.toast.error(this.translate.instant('CHART.UPDATE_REASON_REQUIRED'));
      return;
    }
    this.updateSaving.set(true);
    this.patientService
      .createChartUpdate(this.patientId, { ...this.updateForm, hospitalId: this.hospitalId() })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('CHART.UPDATE_CREATED'));
          this.updateSaving.set(false);
          this.closeUpdateModal();
          this.loadUpdates();
        },
        error: () => {
          this.toast.error(this.translate.instant('CHART.UPDATE_SAVE_ERROR'));
          this.updateSaving.set(false);
        },
      });
  }

  /* ── Timeline ── */

  openTimelineReason(): void {
    this.timelineReason = '';
    this.showTimelineReason.set(true);
  }

  closeTimelineReason(): void {
    this.showTimelineReason.set(false);
  }

  submitTimelineReason(): void {
    if (!this.timelineReason.trim()) {
      this.toast.error(this.translate.instant('CHART.REASON_REQUIRED'));
      return;
    }
    this.showTimelineReason.set(false);
    this.timelineLoading.set(true);
    this.patientService.getDoctorTimeline(this.patientId, this.timelineReason.trim()).subscribe({
      next: (timeline) => {
        this.timeline.set(timeline);
        this.timelineLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('CHART.TIMELINE_LOAD_ERROR'));
        this.timelineLoading.set(false);
      },
    });
  }

  timelineIcon(category: string): string {
    switch (category) {
      case 'ENCOUNTER':
        return 'stethoscope';
      case 'PRESCRIPTION':
        return 'medication';
      case 'LAB_RESULT':
        return 'science';
      case 'ALLERGY':
        return 'warning';
      default:
        return 'event_note';
    }
  }

  severityClass(severity?: string): string {
    switch (severity) {
      case 'LIFE_THREATENING':
      case 'SEVERE':
        return 'sev-badge sev-high';
      case 'MODERATE':
        return 'sev-badge sev-mid';
      case 'MILD':
        return 'sev-badge sev-low';
      default:
        return 'sev-badge';
    }
  }

  problemStatusClass(status?: string): string {
    switch (status) {
      case 'ACTIVE':
      case 'RECURRENCE':
        return 'status-badge status-active-problem';
      case 'RESOLVED':
        return 'status-badge status-resolved';
      default:
        return 'status-badge';
    }
  }
}
