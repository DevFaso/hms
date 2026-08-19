import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  MedicalHistoryService,
  FamilyHistoryRequest,
  FamilyHistoryResponse,
  ImmunizationRequest,
  ImmunizationResponse,
  SocialHistoryRequest,
  SocialHistoryResponse,
} from '../../services/medical-history.service';
import { AuthService } from '../../auth/auth.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';

type HistorySection = 'social' | 'family' | 'immunizations';
type FamilyFilter = 'all' | 'genetic' | 'screening-needed';

/**
 * Staff-facing structured medical history (social / family / immunizations)
 * against /medical-history. Reads are limited to DOCTOR/NURSE/MIDWIFE/
 * LAB_SCIENTIST/PHARMACIST on the backend — admins can only delete, so this
 * tab is hidden from them entirely.
 */
@Component({
  selector: 'app-medical-history-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './medical-history-tab.component.html',
  styleUrl: './medical-history-tab.component.scss',
})
export class MedicalHistoryTabComponent implements OnInit {
  @Input({ required: true }) patientId = '';

  private readonly historyService = inject(MedicalHistoryService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /** Social/family writes; immunization writes additionally allow PHARMACIST. */
  readonly canEditSocialFamily = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
  ]);
  readonly canEditImmunizations = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_PHARMACIST',
  ]);
  /** mark-reminder-sent ∩ read roles. */
  readonly canMarkReminder = this.roleContext.hasAnyActiveRole(['ROLE_DOCTOR', 'ROLE_NURSE']);

  section = signal<HistorySection>('social');

  /* ── Social ── */
  currentSocial = signal<SocialHistoryResponse | null>(null);
  socialVersions = signal<SocialHistoryResponse[]>([]);
  socialLoading = signal(false);
  showSocialModal = signal(false);
  socialSaving = signal(false);
  socialForm: SocialHistoryRequest = this.emptySocialForm();

  /* ── Family ── */
  familyFilter = signal<FamilyFilter>('all');
  familyEntries = signal<FamilyHistoryResponse[]>([]);
  familyLoading = signal(false);
  showFamilyModal = signal(false);
  editingFamilyId = signal<string | null>(null);
  familySaving = signal(false);
  familyForm: FamilyHistoryRequest = this.emptyFamilyForm();

  /* ── Immunizations ── */
  immunizations = signal<ImmunizationResponse[]>([]);
  immunizationsLoading = signal(false);
  showImmunizationModal = signal(false);
  editingImmunizationId = signal<string | null>(null);
  immunizationSaving = signal(false);
  immunizationForm: ImmunizationRequest = this.emptyImmunizationForm();
  reminderBusyId = signal<string | null>(null);

  /** Soft-deleted rows come back from the API — filter them here. */
  readonly activeImmunizations = computed(() =>
    this.immunizations().filter((i) => i.active !== false),
  );
  readonly overdueCount = computed(
    () => this.activeImmunizations().filter((i) => this.isOverdue(i)).length,
  );

  ngOnInit(): void {
    this.loadSection();
  }

  setSection(section: HistorySection): void {
    this.section.set(section);
    this.loadSection();
  }

  private loadSection(): void {
    switch (this.section()) {
      case 'social':
        this.loadSocial();
        break;
      case 'family':
        this.loadFamily();
        break;
      case 'immunizations':
        this.loadImmunizations();
        break;
    }
  }

  private hospitalId(): string {
    return this.roleContext.activeHospitalId ?? this.auth.getHospitalId() ?? '';
  }

  private todayIso(): string {
    const d = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  /* ── Social ── */

  loadSocial(): void {
    this.socialLoading.set(true);
    this.historyService.currentSocialHistory(this.patientId).subscribe({
      next: (current) => {
        // Backend returns 200 with an empty body when none exists.
        this.currentSocial.set(current && current.id ? current : null);
        this.socialLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_HISTORY.SOCIAL_LOAD_ERROR'));
        this.socialLoading.set(false);
      },
    });
    this.historyService.socialHistoryForPatient(this.patientId).subscribe({
      next: (versions) => this.socialVersions.set(versions ?? []),
      error: () => this.socialVersions.set([]),
    });
  }

  emptySocialForm(): SocialHistoryRequest {
    return { patientId: '', hospitalId: '', recordedDate: this.todayIso() };
  }

  openSocialForm(): void {
    const current = this.currentSocial();
    // Spread the full current record so unedited fields survive the new
    // version — the backend "create" supersedes the previous record.
    this.socialForm = current
      ? {
          ...current,
          patientId: this.patientId,
          hospitalId: this.hospitalId(),
          recordedDate: this.todayIso(),
        }
      : {
          ...this.emptySocialForm(),
          patientId: this.patientId,
          hospitalId: this.hospitalId(),
        };
    this.showSocialModal.set(true);
  }

  closeSocialModal(): void {
    this.showSocialModal.set(false);
  }

  submitSocial(): void {
    if (!this.socialForm.patientId || !this.socialForm.recordedDate) return;
    this.socialForm.recordedByStaffId = this.auth.getUserProfile()?.staffId ?? undefined;
    this.socialSaving.set(true);
    this.historyService.createSocialHistory(this.socialForm).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('MED_HISTORY.SOCIAL_SAVED'));
        this.socialSaving.set(false);
        this.closeSocialModal();
        this.loadSocial();
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_HISTORY.SOCIAL_SAVE_ERROR'));
        this.socialSaving.set(false);
      },
    });
  }

  /* ── Family ── */

  setFamilyFilter(filter: FamilyFilter): void {
    this.familyFilter.set(filter);
    this.loadFamily();
  }

  loadFamily(): void {
    this.familyLoading.set(true);
    const filter = this.familyFilter();
    const source =
      filter === 'genetic'
        ? this.historyService.geneticFamilyHistory(this.patientId)
        : filter === 'screening-needed'
          ? this.historyService.screeningNeededFamilyHistory(this.patientId)
          : this.historyService.familyHistoryForPatient(this.patientId);
    source.subscribe({
      next: (entries) => {
        this.familyEntries.set((entries ?? []).filter((e) => e.active !== false));
        this.familyLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_HISTORY.FAMILY_LOAD_ERROR'));
        this.familyLoading.set(false);
      },
    });
  }

  emptyFamilyForm(): FamilyHistoryRequest {
    return {
      patientId: '',
      hospitalId: '',
      recordedDate: this.todayIso(),
      relationship: '',
      conditionDisplay: '',
    };
  }

  openCreateFamily(): void {
    this.familyForm = {
      ...this.emptyFamilyForm(),
      patientId: this.patientId,
      hospitalId: this.hospitalId(),
    };
    this.editingFamilyId.set(null);
    this.showFamilyModal.set(true);
  }

  openEditFamily(entry: FamilyHistoryResponse): void {
    // PUT is a full replace — round-trip the complete record.
    this.familyForm = {
      ...entry,
      patientId: entry.patientId,
      hospitalId: entry.hospitalId ?? this.hospitalId(),
      recordedDate: entry.recordedDate ?? this.todayIso(),
      relationship: entry.relationship ?? '',
      conditionDisplay: entry.conditionDisplay ?? '',
    };
    this.editingFamilyId.set(entry.id);
    this.showFamilyModal.set(true);
  }

  closeFamilyModal(): void {
    this.showFamilyModal.set(false);
  }

  submitFamily(): void {
    const form = this.familyForm;
    if (!form.relationship.trim() || !form.conditionDisplay.trim()) {
      this.toast.error(this.translate.instant('MED_HISTORY.FAMILY_REQUIRED_FIELDS'));
      return;
    }
    form.recordedByStaffId = this.auth.getUserProfile()?.staffId ?? form.recordedByStaffId;
    this.familySaving.set(true);
    const editingId = this.editingFamilyId();
    const op = editingId
      ? this.historyService.updateFamilyHistory(editingId, form)
      : this.historyService.createFamilyHistory(form);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            editingId ? 'MED_HISTORY.FAMILY_UPDATED' : 'MED_HISTORY.FAMILY_CREATED',
          ),
        );
        this.familySaving.set(false);
        this.closeFamilyModal();
        this.loadFamily();
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_HISTORY.FAMILY_SAVE_ERROR'));
        this.familySaving.set(false);
      },
    });
  }

  /* ── Immunizations ── */

  loadImmunizations(): void {
    this.immunizationsLoading.set(true);
    this.historyService.immunizationsForPatient(this.patientId).subscribe({
      next: (list) => {
        this.immunizations.set(list ?? []);
        this.immunizationsLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_HISTORY.IMMUNIZATIONS_LOAD_ERROR'));
        this.immunizationsLoading.set(false);
      },
    });
  }

  /** The persisted overdue flag is stale — compute from nextDoseDueDate. */
  isOverdue(immunization: ImmunizationResponse): boolean {
    if (!immunization.nextDoseDueDate) return false;
    return immunization.nextDoseDueDate < this.todayIso();
  }

  emptyImmunizationForm(): ImmunizationRequest {
    return {
      patientId: '',
      hospitalId: '',
      vaccineCode: '',
      vaccineDisplay: '',
      administrationDate: this.todayIso(),
      // Uppercase COMPLETED is load-bearing for backend reminders/exports.
      status: 'COMPLETED',
    };
  }

  openCreateImmunization(): void {
    this.immunizationForm = {
      ...this.emptyImmunizationForm(),
      patientId: this.patientId,
      hospitalId: this.hospitalId(),
    };
    this.editingImmunizationId.set(null);
    this.showImmunizationModal.set(true);
  }

  openEditImmunization(immunization: ImmunizationResponse): void {
    // PUT is a full replace — round-trip the complete record.
    this.immunizationForm = {
      ...immunization,
      patientId: immunization.patientId,
      hospitalId: immunization.hospitalId ?? this.hospitalId(),
      vaccineCode: immunization.vaccineCode ?? '',
      vaccineDisplay: immunization.vaccineDisplay ?? '',
      administrationDate: immunization.administrationDate ?? this.todayIso(),
      status: immunization.status ?? 'COMPLETED',
    };
    this.editingImmunizationId.set(immunization.id);
    this.showImmunizationModal.set(true);
  }

  closeImmunizationModal(): void {
    this.showImmunizationModal.set(false);
  }

  submitImmunization(): void {
    const form = this.immunizationForm;
    if (!form.vaccineCode.trim() || !form.vaccineDisplay.trim() || !form.administrationDate) {
      this.toast.error(this.translate.instant('MED_HISTORY.IMMUNIZATION_REQUIRED_FIELDS'));
      return;
    }
    form.administeredByStaffId = this.auth.getUserProfile()?.staffId ?? form.administeredByStaffId;
    this.immunizationSaving.set(true);
    const editingId = this.editingImmunizationId();
    const op = editingId
      ? this.historyService.updateImmunization(editingId, form)
      : this.historyService.createImmunization(form);
    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            editingId ? 'MED_HISTORY.IMMUNIZATION_UPDATED' : 'MED_HISTORY.IMMUNIZATION_CREATED',
          ),
        );
        this.immunizationSaving.set(false);
        this.closeImmunizationModal();
        this.loadImmunizations();
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_HISTORY.IMMUNIZATION_SAVE_ERROR'));
        this.immunizationSaving.set(false);
      },
    });
  }

  markReminderSent(immunization: ImmunizationResponse): void {
    this.reminderBusyId.set(immunization.id);
    this.historyService.markReminderSent(immunization.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('MED_HISTORY.REMINDER_MARKED'));
        this.reminderBusyId.set(null);
        // Endpoint returns an empty body — refetch for the updated flags.
        this.loadImmunizations();
      },
      error: () => {
        this.toast.error(this.translate.instant('MED_HISTORY.REMINDER_ERROR'));
        this.reminderBusyId.set(null);
      },
    });
  }

  doseLabel(immunization: ImmunizationResponse): string {
    if (!immunization.doseNumber) return '—';
    return immunization.totalDosesInSeries
      ? `${immunization.doseNumber} / ${immunization.totalDosesInSeries}`
      : `${immunization.doseNumber}`;
  }
}
