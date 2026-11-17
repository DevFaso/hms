import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ToastService } from '../core/toast.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { RoleContextService } from '../core/role-context.service';
import { ProfileService } from '../services/profile.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { PhoneFieldDirective } from '../shared/forms/phone-field.directive';
import { missingRequiredMessage } from '../shared/required-fields';
import { storedLang } from '../shared/i18n/app-locale';

/** The backend's DepartmentResponseDTO (guarded by scripts/check-department-dto.test.mjs). */
export interface Department {
  id: string;
  name: string;
  departmentCode: string;
  description?: string;
  headOfDepartmentName?: string;
  phoneNumber?: string;
  email?: string;
  staffCount?: number;
  bedCount?: number;
  active: boolean;
  hospitalId?: string;
  hospitalName?: string;
  translations?: string[];
  translationEntries?: DepartmentTranslation[];
}

export interface DepartmentTranslation {
  id?: string;
  departmentId?: string;
  languageCode: string;
  name: string;
  description?: string | null;
}

/** The languages the edit form shows a name for. */
export const DEPARTMENT_NAME_LANGUAGES = ['fr', 'en', 'es'] as const;

interface DepartmentRequest {
  hospitalId: string;
  name: string;
  code: string;
  description?: string;
  phoneNumber?: string;
  email?: string;
  active: boolean;
  headOfDepartmentEmail?: string;
  bedCapacity?: number | null;
  translations?: DepartmentTranslation[];
}

@Component({
  selector: 'app-department-list',
  standalone: true,
  imports: [
    PhoneFieldDirective,
    SubmitGuardDirective,
    ModalDialogDirective,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
  ],
  templateUrl: './department-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './department-list.scss',
})
export class DepartmentListComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly roleContext = inject(RoleContextService);
  private readonly profileService = inject(ProfileService);
  private readonly translate = inject(TranslateService);

  departments = signal<Department[]>([]);
  filtered = signal<Department[]>([]);
  searchTerm = '';
  loading = signal(true);

  hospitals = signal<HospitalResponse[]>([]);

  // Staff for head-of-department dropdown (keyed by hospitalId selection)
  staffForHospital = signal<StaffResponse[]>([]);
  staffLoading = signal(false);

  // Create / Edit
  showModal = signal(false);
  editing = signal<Department | null>(null);
  saving = signal(false);
  form: DepartmentRequest = this.emptyForm();
  /** One name per language (FR / EN / ES). Blank keeps the stored value. */
  readonly nameLanguages = [
    { code: 'fr', labelKey: 'DEPARTMENTS.NAME_IN_FR' },
    { code: 'en', labelKey: 'DEPARTMENTS.NAME_IN_EN' },
    { code: 'es', labelKey: 'DEPARTMENTS.NAME_IN_ES' },
  ];
  langNames: Record<string, string> = this.emptyLangNames();

  /** Receptionist gets read-only access — no create/edit/delete */
  canManage = !this.roleContext.isReceptionist();

  // Delete
  showDeleteConfirm = signal(false);
  deletingDept = signal<Department | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.loadDepartments();
    this.loadAssignedHospitals();
  }

  /** ── TENANT ISOLATION: only SUPER_ADMIN may choose from all hospitals ── */
  private loadAssignedHospitals(): void {
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe({
        next: (data) => this.hospitals.set(data),
      });
    } else {
      // Non-super-admin: use /me/hospital (tenant-safe, allowed by SecurityConfig)
      // instead of /hospitals/{id} which is SUPER_ADMIN-only.
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals.set([h]);
          this.form.hospitalId = h.id;
          // Also ensure roleContext has the hospital ID for other components
          if (!this.roleContext.activeHospitalId) {
            this.roleContext.activeHospitalId = h.id;
          }
          this.loadStaffForHospital(h.id);
        },
        error: () => {
          // Last-resort fallback: try /me/assignments
          this.profileService.getAssignments().subscribe({
            next: (assignments) => {
              const active = assignments.filter((a) => a.active && a.hospitalId);
              if (active.length > 0) {
                const hId = active[0].hospitalId!;
                const hName = active[0].hospitalName ?? 'Assigned Hospital';
                this.roleContext.activeHospitalId = hId;
                this.roleContext.setPermittedHospitalIds(
                  active.map((a) => a.hospitalId!).filter((v, i, arr) => arr.indexOf(v) === i),
                );
                this.hospitals.set([{ id: hId, name: hName } as HospitalResponse]);
                this.form.hospitalId = hId;
                this.loadStaffForHospital(hId);
              }
            },
          });
        },
      });
    }
  }

  /** Fetch a single hospital by ID and lock it into the form (SUPER_ADMIN only). */
  private fetchAndLockHospital(hospitalId: string): void {
    this.hospitalService.getById(hospitalId).subscribe({
      next: (h) => {
        this.hospitals.set([h]);
        this.form.hospitalId = h.id;
        this.loadStaffForHospital(h.id);
      },
    });
  }

  /** Name shown in the locked hospital display for non-SUPER_ADMIN */
  get lockedHospitalName(): string {
    const h = this.hospitals();
    return h.length === 1 ? h[0].name : this.translate.instant('DEPARTMENTS.NO_HOSPITAL_ASSIGNED');
  }

  /** Whether the hospital field should be a read-only locked display */
  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  loadDepartments(): void {
    this.loading.set(true);
    this.http.get<{ content: Department[] }>('/departments').subscribe({
      next: (res) => {
        const list = res?.content ?? (Array.isArray(res) ? res : []);
        this.departments.set(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('DEPARTMENTS.TOAST.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.departments());
      return;
    }
    this.filtered.set(
      this.departments().filter(
        (d) =>
          d.name.toLowerCase().includes(term) ||
          (d.departmentCode ?? '').toLowerCase().includes(term) ||
          (d.description?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  // ---------- Create ----------
  openCreate(): void {
    this.form = this.emptyForm();
    this.langNames = this.emptyLangNames();
    // Re-apply the locked hospital for non-super-admin users
    const h = this.hospitals();
    if (this.hospitalLocked && h.length === 1) {
      this.form.hospitalId = h[0].id;
    }
    this.editing.set(null);
    this.staffForHospital.set([]);
    // Pre-load staff for the locked hospital
    if (this.form.hospitalId) {
      this.loadStaffForHospital(this.form.hospitalId);
    }
    this.showModal.set(true);
  }

  // ---------- Edit ----------
  openEdit(dept: Department): void {
    this.editing.set(dept);
    this.form = {
      hospitalId: dept.hospitalId ?? '',
      name: dept.name,
      code: dept.departmentCode ?? '',
      description: dept.description ?? '',
      phoneNumber: dept.phoneNumber ?? '',
      email: dept.email ?? '',
      active: dept.active,
      headOfDepartmentEmail: '',
      bedCapacity: dept.bedCount ?? null,
    };
    this.langNames = this.emptyLangNames();
    for (const t of dept.translationEntries ?? []) {
      const lang = t.languageCode?.toLowerCase();
      if (lang && lang in this.langNames) this.langNames[lang] = t.name ?? '';
    }
    // Pre-load staff for the existing hospital so the dropdown is populated
    if (dept.hospitalId) {
      this.loadStaffForHospital(dept.hospitalId, dept.id);
    }
    this.showModal.set(true);
  }

  /** Called when the hospital <select> changes — load staff and clear head selection */
  onHospitalChange(hospitalId: string): void {
    this.form.headOfDepartmentEmail = '';
    this.staffForHospital.set([]);
    if (hospitalId) {
      this.loadStaffForHospital(hospitalId);
    }
  }

  private loadStaffForHospital(hospitalId: string, departmentId?: string): void {
    const form = this.form;
    this.staffLoading.set(true);
    this.staffService.list(hospitalId).subscribe({
      next: (staff) => {
        if (this.form !== form || form.hospitalId !== hospitalId) return;
        const active = staff.filter((s) => s.active);
        this.staffForHospital.set(active);
        // StaffMapper identifies the actual head by staff ID within the department.
        // A name match can select a different person when the head is inactive.
        if (departmentId) {
          const matches = active.filter(
            (s) => s.departmentId === departmentId && s.headOfDepartment,
          );
          if (matches.length === 1) {
            this.form.headOfDepartmentEmail = matches[0].email;
          }
        }
        this.staffLoading.set(false);
      },
      error: () => {
        if (this.form !== form || form.hospitalId !== hospitalId) return;
        this.staffForHospital.set([]);
        this.staffLoading.set(false);
      },
    });
  }

  /** Auto-uppercase the department code as the user types */
  onCodeInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const upper = input.value.toUpperCase();
    input.value = upper;
    this.form.code = upper;
  }

  closeModal(): void {
    this.showModal.set(false);
    this.editing.set(null);
  }

  submitForm(): void {
    if (!this.form.name || !this.form.code || !this.form.hospitalId) {
      // QA D1358: name the blank fields, not a fixed list.
      this.toast.error(
        missingRequiredMessage(this.translate, [
          { labelKey: 'ENCOUNTERS.HOSPITAL', value: this.form.hospitalId },
          { labelKey: 'DEPARTMENTS.NAME', value: this.form.name },
          { labelKey: 'DEPARTMENTS.CODE', value: this.form.code },
        ]) ?? this.translate.instant('COMMON.SUBMIT_BLOCKED_REQUIRED'),
      );
      return;
    }
    this.saving.set(true);
    const payload: DepartmentRequest = { ...this.form };
    if (!payload.description) delete payload.description;
    if (!payload.phoneNumber) delete payload.phoneNumber;
    if (!payload.email) delete payload.email;
    if (!payload.headOfDepartmentEmail) delete payload.headOfDepartmentEmail;
    if (payload.bedCapacity === null || (payload.bedCapacity as unknown) === '') {
      delete payload.bedCapacity;
    }
    const translations = this.buildTranslations(this.editing());
    if (translations) payload.translations = translations;
    else delete payload.translations;

    const existing = this.editing();
    const request$ = existing
      ? this.http.put<Department>(`/departments/${existing.id}`, payload)
      : this.http.post<Department>('/departments', payload);

    request$.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(
            existing ? 'DEPARTMENTS.TOAST.UPDATED' : 'DEPARTMENTS.TOAST.CREATED',
          ),
        );
        this.closeModal();
        this.saving.set(false);
        this.loadDepartments();
      },
      error: (err) => {
        const body = err?.error;
        let msg = `Failed to ${existing ? 'update' : 'create'} department`;
        if (body?.fieldErrors) {
          msg = Object.values(body.fieldErrors).join('. ');
        } else if (body?.message) {
          msg = body.message;
        }
        this.toast.error(msg);
        this.saving.set(false);
      },
    });
  }

  // ---------- Delete ----------
  confirmDelete(dept: Department): void {
    this.deletingDept.set(dept);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingDept.set(null);
  }

  executeDelete(): void {
    const dept = this.deletingDept();
    if (!dept) return;
    this.deleting.set(true);
    this.http.delete(`/departments/${dept.id}`).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('DEPARTMENTS.TOAST.DELETED'));
        this.cancelDelete();
        this.deleting.set(false);
        this.loadDepartments();
      },
      error: (err) => {
        // Delete-failure path needs its own key — falling back to
        // SAVE_FAILED here would surface "Failed to save department" on
        // a delete error (Copilot review on PR #256).
        this.toast.error(
          err?.error?.message ?? this.translate.instant('DEPARTMENTS.TOAST.DELETE_FAILED'),
        );
        this.deleting.set(false);
      },
    });
  }

  // ---------- Helpers ----------
  private emptyLangNames(): Record<string, string> {
    return { fr: '', en: '', es: '' };
  }

  /**
   * The full translation list the update replaces. A blank language keeps the
   * stored value; a language the form does not show (not FR/EN/ES) is passed
   * through; the editor's own language follows the main name when only that
   * was changed. Null when there is nothing to send (keeps what is stored).
   */
  private buildTranslations(dept: Department | null): DepartmentTranslation[] | null {
    const stored = dept?.translationEntries ?? [];
    const own = storedLang();
    const out: DepartmentTranslation[] = [];
    for (const t of stored) {
      const lang = t.languageCode?.toLowerCase();
      if (!(DEPARTMENT_NAME_LANGUAGES as readonly string[]).includes(lang)) {
        out.push({ languageCode: t.languageCode, name: t.name, description: t.description });
      }
    }
    for (const lang of DEPARTMENT_NAME_LANGUAGES) {
      const prev = stored.find((t) => t.languageCode?.toLowerCase() === lang);
      const typed = (this.langNames[lang] ?? '').trim();
      let name = typed || prev?.name || '';
      let description = prev?.description;
      if (lang === own) {
        // Match Accept-Language, including when the API displayed a fallback
        // translation. Main-field edits must survive the full-list replacement.
        const mainNameChanged = dept && this.form.name !== dept.name;
        const descriptionChanged = dept && this.form.description !== (dept.description ?? '');
        if (!prev) description = this.form.description ?? '';
        if (mainNameChanged && (!typed || typed === prev?.name)) name = this.form.name;
        if (descriptionChanged) {
          description = this.form.description ?? '';
          if (!name) name = this.form.name;
        }
      }
      if (name) out.push({ languageCode: lang, name, description });
    }
    return out.length > 0 ? out : null;
  }

  private emptyForm(): DepartmentRequest {
    return {
      hospitalId: '',
      name: '',
      code: '',
      description: '',
      phoneNumber: '',
      email: '',
      active: true,
      headOfDepartmentEmail: '',
      bedCapacity: null,
    };
  }
}
