import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { UserService, UserSummary, AdminRegisterRequest } from '../services/user.service';
import { RoleService, RoleResponse } from '../services/role.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';

const MEDICAL_ROLE_CODES = new Set([
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_LAB_SCIENTIST',
  'ROLE_PHARMACIST',
]);

const JOB_TITLES = [
  'DOCTOR',
  'PHYSICIAN',
  'NURSE_PRACTITIONER',
  'NURSE',
  'MIDWIFE',
  'HOSPITAL_ADMIN',
  'ADMINISTRATIVE_STAFF',
  'TECHNICIAN',
  'PHARMACIST',
  'LAB_TECHNICIAN',
  'RECEPTIONIST',
  'SURGEON',
  'HOSPITAL_ADMINISTRATOR',
  'LABORATORY_SCIENTIST',
  'RADIOLOGIST',
  'ANESTHESIOLOGIST',
  'PHYSIOTHERAPIST',
  'PSYCHOLOGIST',
  'SOCIAL_WORKER',
  'BILLING_SPECIALIST',
  'IT_SUPPORT',
];

const EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'LOCUM', 'INTERN'];

const SPECIALIZATIONS = [
  'GENERAL_PRACTICE',
  'CARDIOLOGY',
  'NEUROLOGY',
  'PEDIATRICS',
  'ORTHOPEDICS',
  'DERMATOLOGY',
  'GYNECOLOGY',
  'ONCOLOGY',
  'RADIOLOGY',
  'PSYCHIATRY',
  'GENERAL_NURSE',
  'ICU_NURSE',
  'PEDIATRIC_NURSE',
  'SURGICAL_NURSE',
  'CLINICAL_PHARMACY',
  'HOSPITAL_PHARMACY',
  'LAB_TECHNICIAN',
  'PATHOLOGY',
  'MICROBIOLOGY',
  'OTHER',
];

@Component({
  selector: 'app-user-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './user-list.html',
  styleUrl: './user-list.scss',
})
export class UserListComponent implements OnInit {
  private readonly userService = inject(UserService);
  private readonly roleService = inject(RoleService);
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);

  users = signal<UserSummary[]>([]);
  filtered = signal<UserSummary[]>([]);
  loading = signal(true);
  searchTerm = '';

  currentPage = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);

  showCreate = signal(false);
  saving = signal(false);
  editing = signal<UserSummary | null>(null);

  // Delete
  showDeleteConfirm = signal(false);
  deletingUser = signal<UserSummary | null>(null);
  deleting = signal(false);

  // Lookup data for dropdowns
  availableRoles = signal<RoleResponse[]>([]);
  availableHospitals = signal<{ id: string; name: string }[]>([]);

  // Form fields
  createForm: AdminRegisterRequest = this.freshForm();
  selectedRoles: string[] = [];

  // Enum options for dropdowns
  readonly jobTitles = JOB_TITLES;
  readonly employmentTypes = EMPLOYMENT_TYPES;
  readonly specializations = SPECIALIZATIONS;

  /** True when any selected role is a medical role requiring a license number */
  get licenseRequired(): boolean {
    return this.selectedRoles.some((r) => MEDICAL_ROLE_CODES.has(r));
  }

  ngOnInit(): void {
    this.loadUsers();
    this.loadRoles();
    this.loadHospitals();
  }

  loadUsers(page = 0): void {
    this.loading.set(true);
    this.userService.list(page, 20).subscribe({
      next: (res) => {
        this.users.set(res.content);
        this.currentPage.set(res.number);
        this.totalPages.set(res.totalPages);
        this.totalElements.set(res.totalElements);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load users');
        this.loading.set(false);
      },
    });
  }

  loadRoles(): void {
    this.roleService.list().subscribe({
      next: (roles) => this.availableRoles.set(roles),
      error: () => {
        /* silent — roles dropdown just stays empty */
      },
    });
  }

  loadHospitals(): void {
    this.hospitalService.list().subscribe({
      next: (hospitals: HospitalResponse[]) =>
        this.availableHospitals.set(hospitals.map((h) => ({ id: h.id, name: h.name }))),
      error: () => {
        /* silent */
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.users());
      return;
    }
    this.filtered.set(
      this.users().filter(
        (u) =>
          u.username.toLowerCase().includes(term) ||
          u.email.toLowerCase().includes(term) ||
          u.firstName.toLowerCase().includes(term) ||
          u.lastName.toLowerCase().includes(term) ||
          (u.roleName?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  getInitials(u: UserSummary): string {
    return `${u.firstName?.charAt(0) ?? ''}${u.lastName?.charAt(0) ?? ''}`.toUpperCase() || '?';
  }

  openCreate(): void {
    this.createForm = this.freshForm();
    this.selectedRoles = [];
    this.editing.set(null);
    this.showCreate.set(true);
  }

  openEdit(user: UserSummary): void {
    this.editing.set(user);
    this.createForm = {
      username: user.username,
      email: user.email,
      password: '',
      firstName: user.firstName,
      lastName: user.lastName,
      phoneNumber: '',
      roleNames: user.roleName ? [user.roleName] : [],
    };
    this.selectedRoles = user.roleName ? [user.roleName] : [];
    this.showCreate.set(true);
  }

  closeCreate(): void {
    this.showCreate.set(false);
    this.editing.set(null);
  }

  onRoleToggle(roleCode: string, checked: boolean): void {
    if (checked) {
      if (!this.selectedRoles.includes(roleCode)) {
        this.selectedRoles.push(roleCode);
      }
    } else {
      this.selectedRoles = this.selectedRoles.filter((r) => r !== roleCode);
    }
    // Clear licenseNumber when no medical role selected
    if (!this.licenseRequired) {
      this.createForm.licenseNumber = undefined;
    }
  }

  submitCreate(): void {
    const isEdit = !!this.editing();
    if (
      !this.createForm.username ||
      !this.createForm.email ||
      (!isEdit && !this.createForm.password) ||
      !this.createForm.firstName ||
      !this.createForm.lastName
    ) {
      this.toast.error('All required fields must be filled');
      return;
    }
    if (this.selectedRoles.length === 0) {
      this.toast.error('At least one role must be selected');
      return;
    }
    if (this.licenseRequired && !this.createForm.licenseNumber?.trim()) {
      this.toast.error('License number is required for medical roles');
      return;
    }

    this.createForm.roleNames = [...this.selectedRoles];

    this.saving.set(true);
    const existing = this.editing();

    // Strip empty strings for optional UUID / enum fields so Jackson on the backend
    // doesn't attempt to deserialize "" as a UUID or enum value (→ 400 parse error).
    const payload: AdminRegisterRequest = { ...this.createForm };
    if (!payload.hospitalId) delete payload.hospitalId;
    if (!payload.jobTitle) delete payload.jobTitle;
    if (!payload.employmentType) delete payload.employmentType;
    if (!payload.specialization) delete payload.specialization;
    if (!payload.licenseNumber?.trim()) delete payload.licenseNumber;
    if (!payload.departmentId) delete payload.departmentId;

    const op = existing
      ? this.userService.update(existing.id, payload)
      : this.userService.adminRegister(payload);

    op.subscribe({
      next: () => {
        this.toast.success(existing ? 'User updated' : 'User created');
        this.showCreate.set(false);
        this.saving.set(false);
        this.editing.set(null);
        this.loadUsers();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Operation failed');
        this.saving.set(false);
      },
    });
  }

  confirmDelete(user: UserSummary): void {
    this.deletingUser.set(user);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingUser.set(null);
  }

  executeDelete(): void {
    const user = this.deletingUser();
    if (!user) return;
    this.deleting.set(true);
    this.userService.delete(user.id).subscribe({
      next: () => {
        this.toast.success('User deleted');
        this.showDeleteConfirm.set(false);
        this.deleting.set(false);
        this.deletingUser.set(null);
        this.loadUsers();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to delete user');
        this.deleting.set(false);
      },
    });
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.loadUsers(page);
    }
  }

  formatEnumLabel(value: string): string {
    return value
      .replaceAll('_', ' ')
      .toLowerCase()
      .replaceAll(/\b\w/g, (c) => c.toUpperCase());
  }

  private freshForm(): AdminRegisterRequest {
    return {
      username: '',
      email: '',
      password: '',
      firstName: '',
      lastName: '',
      phoneNumber: '',
      roleNames: [],
    };
  }
}
