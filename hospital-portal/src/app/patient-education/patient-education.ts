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
  EducationService,
  EducationCategory,
  EducationComprehensionStatus,
  EducationProgress,
  EducationProgressRequest,
  EducationResource,
  EducationResourceRequest,
  EducationResourceType,
} from '../services/education.service';
import { PatientResponse } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

/**
 * Patient-education workspace (Phase 3 task 18): resource library + assign/
 * progress views against /patient-education. Backend role quirks honored:
 * writes are SUPER_ADMIN/DOCTOR/NURSE (midwives read-only, HOSPITAL_ADMIN has
 * no access at all), delete is SUPER_ADMIN-only. Q&A and visit-documentation
 * blocks are deliberately deferred (separate concerns w/ backend defects).
 */
@Component({
  selector: 'app-patient-education',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './patient-education.html',
  styleUrl: './patient-education.scss',
})
export class PatientEducationComponent implements OnInit {
  private readonly educationService = inject(EducationService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly canManageResources = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_SUPER_ADMIN',
  ]);
  readonly canDeleteResource = this.roleContext.hasAnyActiveRole(['ROLE_SUPER_ADMIN']);
  /** Progress reads/updates exclude MIDWIFE and RECEPTIONIST. */
  readonly canTrackProgress = this.canManageResources;

  activeTab = signal<'resources' | 'progress'>('resources');

  /* ── Resources ── */
  resources = signal<EducationResource[]>([]);
  resourcesLoading = signal(false);
  resourcesError = signal(false);
  searchTerm = signal('');
  categoryFilter = signal<EducationCategory | ''>('');
  typeFilter = signal<EducationResourceType | ''>('');

  readonly categories: EducationCategory[] = [
    'PRENATAL_CARE',
    'NUTRITION',
    'EXERCISE',
    'LABOR_AND_DELIVERY',
    'POSTPARTUM_CARE',
    'BREASTFEEDING',
    'NEWBORN_CARE',
    'MENTAL_HEALTH',
    'WARNING_SIGNS',
    'BIRTH_PLAN',
    'PRENATAL_VITAMINS',
    'MANAGING_DISCOMFORT',
    'HIGH_RISK_PREGNANCY',
    'ULTRASOUND_SCANS',
    'GENETIC_SCREENING',
  ];
  readonly resourceTypes: EducationResourceType[] = [
    'ARTICLE',
    'VIDEO',
    'INTERACTIVE_MODULE',
    'INFOGRAPHIC',
    'CHECKLIST',
    'FAQ',
    'PODCAST',
  ];
  readonly comprehensionStatuses: EducationComprehensionStatus[] = [
    'NOT_STARTED',
    'IN_PROGRESS',
    'COMPLETED',
    'CONFIRMED_UNDERSTANDING',
    'NEEDS_CLARIFICATION',
    'FEEDBACK_PROVIDED',
  ];

  /** Backend list has no paging/search — filter client-side. */
  readonly filteredResources = computed(() => {
    const term = this.searchTerm().toLowerCase().trim();
    const category = this.categoryFilter();
    const type = this.typeFilter();
    return this.resources().filter(
      (r) =>
        (!category || r.category === category) &&
        (!type || r.resourceType === type) &&
        (!term ||
          r.title.toLowerCase().includes(term) ||
          (r.description ?? '').toLowerCase().includes(term)),
    );
  });

  /** id → title join for the progress table. */
  private readonly resourceTitles = computed(
    () => new Map(this.resources().map((r) => [r.id, r.title])),
  );

  showResourceModal = signal(false);
  editingResourceId = signal<string | null>(null);
  resourceSaving = signal(false);
  resourceForm: EducationResourceRequest = this.emptyResourceForm();

  deleteTarget = signal<EducationResource | null>(null);
  deleteBusy = signal(false);

  /* ── Progress ── */
  progressPatient = signal<PatientResponse | null>(null);
  progressEntries = signal<EducationProgress[]>([]);
  progressLoading = signal(false);

  showAssignModal = signal(false);
  assignResourceId = '';
  assignBusy = signal(false);

  showProgressModal = signal(false);
  editingProgress = signal<EducationProgress | null>(null);
  progressForm: EducationProgressRequest = { resourceId: '' };
  progressSaving = signal(false);

  ngOnInit(): void {
    this.loadResources();
  }

  setTab(tab: 'resources' | 'progress'): void {
    this.activeTab.set(tab);
  }

  loadResources(): void {
    this.resourcesLoading.set(true);
    this.resourcesError.set(false);
    this.educationService.listResources().subscribe({
      next: (resources) => {
        this.resources.set(resources ?? []);
        this.resourcesLoading.set(false);
      },
      error: () => {
        this.resourcesError.set(true);
        this.resourcesLoading.set(false);
        this.toast.error(this.translate.instant('EDUCATION.LOAD_ERROR'));
      },
    });
  }

  resourceTitle(resourceId: string): string {
    return this.resourceTitles().get(resourceId) ?? '—';
  }

  statusClass(status: EducationComprehensionStatus | undefined): string {
    switch (status) {
      case 'COMPLETED':
      case 'CONFIRMED_UNDERSTANDING':
        return 'status-badge status-completed';
      case 'IN_PROGRESS':
      case 'FEEDBACK_PROVIDED':
        return 'status-badge status-in-progress';
      case 'NEEDS_CLARIFICATION':
        return 'status-badge status-warning';
      default:
        return 'status-badge status-submitted';
    }
  }

  /* ── Resource CRUD ── */

  emptyResourceForm(): EducationResourceRequest {
    return {
      title: '',
      resourceType: 'ARTICLE',
      category: 'PRENATAL_CARE',
      primaryLanguage: 'fr',
      isEvidenceBased: false,
      isHighRiskRelevant: false,
      isWarningSignContent: false,
    };
  }

  openCreateResource(): void {
    this.resourceForm = this.emptyResourceForm();
    this.editingResourceId.set(null);
    this.showResourceModal.set(true);
  }

  openEditResource(resource: EducationResource): void {
    this.resourceForm = {
      title: resource.title,
      description: resource.description ?? '',
      resourceType: resource.resourceType,
      category: resource.category,
      contentUrl: resource.contentUrl ?? '',
      textContent: resource.textContent ?? '',
      videoUrl: resource.videoUrl ?? '',
      estimatedDuration: resource.estimatedDuration,
      isEvidenceBased: resource.isEvidenceBased ?? false,
      evidenceSource: resource.evidenceSource ?? '',
      isHighRiskRelevant: resource.isHighRiskRelevant ?? false,
      isWarningSignContent: resource.isWarningSignContent ?? false,
      primaryLanguage: resource.primaryLanguage ?? 'fr',
    };
    this.editingResourceId.set(resource.id);
    this.showResourceModal.set(true);
  }

  closeResourceModal(): void {
    this.showResourceModal.set(false);
  }

  submitResource(): void {
    if (!this.resourceForm.title.trim()) {
      this.toast.error(this.translate.instant('EDUCATION.TITLE_REQUIRED'));
      return;
    }
    this.resourceSaving.set(true);
    const editingId = this.editingResourceId();
    const op = editingId
      ? this.educationService.updateResource(editingId, this.resourceForm)
      : this.educationService.createResource(this.resourceForm);
    op.subscribe({
      next: (saved) => {
        this.toast.success(
          this.translate.instant(editingId ? 'EDUCATION.UPDATED' : 'EDUCATION.CREATED'),
        );
        this.resourceSaving.set(false);
        this.closeResourceModal();
        this.resources.update((list) =>
          editingId ? list.map((r) => (r.id === saved.id ? saved : r)) : [saved, ...list],
        );
      },
      error: () => {
        this.toast.error(this.translate.instant('EDUCATION.SAVE_ERROR'));
        this.resourceSaving.set(false);
      },
    });
  }

  confirmDelete(resource: EducationResource): void {
    this.deleteTarget.set(resource);
  }

  closeDelete(): void {
    this.deleteTarget.set(null);
  }

  submitDelete(): void {
    const target = this.deleteTarget();
    if (!target) return;
    this.deleteBusy.set(true);
    this.educationService.deleteResource(target.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('EDUCATION.DELETED'));
        this.deleteBusy.set(false);
        this.closeDelete();
        // Soft delete — the active-only list will no longer include it.
        this.resources.update((list) => list.filter((r) => r.id !== target.id));
      },
      error: () => {
        this.toast.error(this.translate.instant('EDUCATION.DELETE_ERROR'));
        this.deleteBusy.set(false);
      },
    });
  }

  /* ── Progress ── */

  onProgressPatientPicked(p: PatientResponse | null): void {
    this.progressPatient.set(p);
    this.progressEntries.set([]);
    if (p) this.loadProgress();
  }

  loadProgress(): void {
    const patient = this.progressPatient();
    if (!patient) return;
    this.progressLoading.set(true);
    this.educationService.progressForPatient(patient.id).subscribe({
      next: (entries) => {
        this.progressEntries.set(entries ?? []);
        this.progressLoading.set(false);
      },
      error: () => {
        this.progressEntries.set([]);
        this.progressLoading.set(false);
        this.toast.error(this.translate.instant('EDUCATION.PROGRESS_LOAD_ERROR'));
      },
    });
  }

  openAssign(): void {
    this.assignResourceId = '';
    this.showAssignModal.set(true);
  }

  closeAssign(): void {
    this.showAssignModal.set(false);
  }

  submitAssign(): void {
    const patient = this.progressPatient();
    if (!patient || !this.assignResourceId) return;
    this.assignBusy.set(true);
    this.educationService
      .trackProgress(patient.id, {
        resourceId: this.assignResourceId,
        comprehensionStatus: 'NOT_STARTED',
      })
      .subscribe({
        next: (created) => {
          this.toast.success(this.translate.instant('EDUCATION.ASSIGNED'));
          this.assignBusy.set(false);
          this.closeAssign();
          this.progressEntries.update((list) => {
            const existing = list.some((e) => e.id === created.id);
            return existing
              ? list.map((e) => (e.id === created.id ? created : e))
              : [created, ...list];
          });
        },
        error: () => {
          this.toast.error(this.translate.instant('EDUCATION.ASSIGN_ERROR'));
          this.assignBusy.set(false);
        },
      });
  }

  openProgressUpdate(entry: EducationProgress): void {
    this.editingProgress.set(entry);
    this.progressForm = {
      resourceId: entry.resourceId,
      comprehensionStatus: entry.comprehensionStatus ?? 'IN_PROGRESS',
      progressPercentage: entry.progressPercentage ?? undefined,
      rating: entry.rating ?? undefined,
      confirmedUnderstanding: entry.confirmedUnderstanding ?? false,
      needsClarification: entry.needsClarification ?? false,
      clarificationRequest: entry.clarificationRequest ?? '',
      providerNotes: entry.providerNotes ?? '',
    };
    this.showProgressModal.set(true);
  }

  closeProgressModal(): void {
    this.showProgressModal.set(false);
    this.editingProgress.set(null);
  }

  submitProgressUpdate(): void {
    const entry = this.editingProgress();
    if (!entry) return;
    this.progressSaving.set(true);
    this.educationService.updateProgress(entry.id, this.progressForm).subscribe({
      next: (updated) => {
        this.toast.success(this.translate.instant('EDUCATION.PROGRESS_UPDATED'));
        this.progressSaving.set(false);
        this.closeProgressModal();
        this.progressEntries.update((list) => list.map((e) => (e.id === updated.id ? updated : e)));
      },
      error: () => {
        this.toast.error(this.translate.instant('EDUCATION.PROGRESS_UPDATE_ERROR'));
        this.progressSaving.set(false);
      },
    });
  }
}
