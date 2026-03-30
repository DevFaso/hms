import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ToastService } from '../core/toast.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { RoleContextService } from '../core/role-context.service';
import { ProfileService } from '../services/profile.service';
import { TranslateModule } from '@ngx-translate/core';

interface Department {
  id: string;
  name: string;
  code: string;
  description?: string;
  headOfDepartment?: string;
  headOfDepartmentEmail?: string;
  phoneNumber?: string;
  email?: string;
  staffCount?: number;
  active: boolean;
  hospitalId?: string;
  hospitalName?: string;
}

interface DepartmentRequest {
  hospitalId: string;
  name: string;
  code: string;
  description?: string;
  phoneNumber?: string;
  email?: string;
  active: boolean;
  headOfDepartmentEmail?: string;
}

@Component({
  selector: 'app-department-list',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './department-list.html',
  styleUrl: './department-list.scss',
})
export class DepartmentListComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly roleContext = inject(RoleContextService);
  private readonly profileService = inject(ProfileService);

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
    return h.length === 1 ? h[0].name : 'No hospital assigned';
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
        this.toast.error('Failed to load departments');
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
          d.code.toLowerCase().includes(term) ||
          (d.description?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  // ---------- Create ----------
  openCreate(): void {
    this.form = this.emptyForm();
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
      code: dept.code,
      description: dept.description ?? '',
      phoneNumber: dept.phoneNumber ?? '',
      email: dept.email ?? '',
      active: dept.active,
      headOfDepartmentEmail: dept.headOfDepartmentEmail ?? '',
    };
    // Pre-load staff for the existing hospital so the dropdown is populated
    if (dept.hospitalId) {
      this.loadStaffForHospital(dept.hospitalId);
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

  private loadStaffForHospital(hospitalId: string): void {
    this.staffLoading.set(true);
    this.staffService.list(hospitalId).subscribe({
      next: (staff) => {
        this.staffForHospital.set(staff.filter((s) => s.active));
        this.staffLoading.set(false);
      },
      error: () => {
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
      this.toast.error('Hospital, name, and code are required');
      return;
    }
    this.saving.set(true);
    const payload: DepartmentRequest = { ...this.form };
    if (!payload.description) delete payload.description;
    if (!payload.phoneNumber) delete payload.phoneNumber;
    if (!payload.email) delete payload.email;
    if (!payload.headOfDepartmentEmail) delete payload.headOfDepartmentEmail;

    const existing = this.editing();
    const request$ = existing
      ? this.http.put<Department>(`/departments/${existing.id}`, payload)
      : this.http.post<Department>('/departments', payload);

    request$.subscribe({
      next: () => {
        this.toast.success(existing ? 'Department updated' : 'Department created');
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
        this.toast.success('Department deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.loadDepartments();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to delete department');
        this.deleting.set(false);
      },
    });
  }

  // ---------- Helpers ----------
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
    };
  }
}
