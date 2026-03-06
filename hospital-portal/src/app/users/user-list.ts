import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { UserService, UserSummary, AdminRegisterRequest } from '../services/user.service';
import { RoleService, RoleResponse } from '../services/role.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

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
export class UserListComponent implements OnInit, OnDestroy {
  private readonly userService = inject(UserService);
  private readonly roleService = inject(RoleService);
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);

  private readonly searchSubject = new Subject<string>();
  private readonly destroy$ = new Subject<void>();

  users = signal<UserSummary[]>([]);
  filtered = signal<UserSummary[]>([]);
  loading = signal(true);
  searchTerm = '';

  /* Filter state */
  roleFilter = '';
  statusFilter = '';
  showFilters = signal(false);

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

  // Restore
  restoring = signal(false);

  /** Per-field validation error messages (from 400/409 backend responses). */
  fieldErrors = signal<Record<string, string>>({});

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
    this.searchSubject
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadUsers(0);
      });
    this.loadUsers();
    this.loadRoles();
    this.loadHospitals();
  }

  loadUsers(page = 0): void {
    this.loading.set(true);

    // Use server-side search when a role filter or search term is active;
    // fall back to the plain list endpoint when no filters are applied.
    const term = this.searchTerm.trim();
    const hasServerFilter = !!this.roleFilter || !!term;

    const request$ = hasServerFilter
      ? this.userService.search(page, 20, {
          ...(this.roleFilter ? { role: this.roleFilter } : {}),
          ...(term ? { name: term } : {}),
        })
      : this.userService.list(page, 20);

    request$.subscribe({
      next: (res) => {
        this.users.set(res.content);
        this.currentPage.set(res.number);
        this.totalPages.set(res.totalPages);
        this.totalElements.set(res.totalElements);
        // Client-side status filter (active/inactive/deleted) is still applied
        // locally because the search endpoint doesn't expose those params.
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
    // ── TENANT ISOLATION: only SUPER_ADMIN loads full hospital list ──
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe({
        next: (hospitals: HospitalResponse[]) =>
          this.availableHospitals.set(hospitals.map((h) => ({ id: h.id, name: h.name }))),
        error: () => {
          /* silent */
        },
      });
    } else {
      const activeId = this.roleContext.activeHospitalId;
      if (activeId) {
        this.hospitalService.getById(activeId).subscribe({
          next: (h) => {
            this.availableHospitals.set([{ id: h.id, name: h.name }]);
            this.createForm.hospitalId = h.id;
          },
        });
      }
    }
  }

  /** True when the hospital field should be a non-editable locked indicator. */
  get hospitalLocked(): boolean {
    return !this.roleContext.isSuperAdmin();
  }

  /** Display name of the single locked hospital. */
  get lockedHospitalName(): string {
    const list = this.availableHospitals();
    return list.length > 0 ? list[0].name : '—';
  }

  applyFilter(): void {
    // Role and search-term filtering is handled server-side via loadUsers().
    // Here we only apply the client-side status filter on the already-loaded page.
    let result = this.users();
    if (this.statusFilter === 'active') {
      result = result.filter((u) => u.active && !u.deleted);
    } else if (this.statusFilter === 'inactive') {
      result = result.filter((u) => !u.active && !u.deleted);
    } else if (this.statusFilter === 'deleted') {
      result = result.filter((u) => u.deleted);
    }
    this.filtered.set(result);
  }

  /** Called when the role filter dropdown changes — reloads from backend page 0. */
  onRoleFilterChange(): void {
    this.currentPage.set(0);
    this.loadUsers(0);
  }

  /** Called when the status filter dropdown changes — client-side only re-filter. */
  onStatusFilterChange(): void {
    this.applyFilter();
  }

  /** Called when the search input changes — debounced 300 ms before reloading from backend. */
  onSearchChange(): void {
    this.searchSubject.next(this.searchTerm);
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  clearFilters(): void {
    this.searchTerm = '';
    this.roleFilter = '';
    this.statusFilter = '';
    this.currentPage.set(0);
    this.loadUsers(0);
  }

  get activeFilterCount(): number {
    return [this.roleFilter, this.statusFilter].filter(Boolean).length;
  }

  getInitials(u: UserSummary): string {
    return `${u.firstName?.charAt(0) ?? ''}${u.lastName?.charAt(0) ?? ''}`.toUpperCase() || '?';
  }

  openCreate(): void {
    this.createForm = this.freshForm();
    this.selectedRoles = [];
    this.editing.set(null);
    this.fieldErrors.set({});
    this.showCreate.set(true);
  }

  openEdit(user: UserSummary): void {
    this.editing.set(user);
    // Pre-populate with summary data; phoneNumber comes from full detail fetch.
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
    this.fieldErrors.set({});
    this.showCreate.set(true);

    // Fetch full user detail to populate phoneNumber and other fields
    // not available in the summary projection.
    this.userService.getById(user.id).subscribe({
      next: (detail) => {
        if (detail.phoneNumber) {
          this.createForm.phoneNumber = detail.phoneNumber;
        }
      },
    });
  }

  closeCreate(): void {
    this.showCreate.set(false);
    this.editing.set(null);
    this.fieldErrors.set({});
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
    this.fieldErrors.set({});
    this.saving.set(true);
    const existing = this.editing();

    // Strip empty strings for optional UUID / enum fields so Jackson on the backend
    // doesn't attempt to deserialize "" as a UUID or enum value (→ 400 parse error).
    // For edits, also strip blank password and phoneNumber so the backend preserves
    // existing values (merge-preserve semantics).
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const payload: Record<string, any> = { ...this.createForm };
    if (!payload['hospitalId']) delete payload['hospitalId'];
    if (!payload['jobTitle']) delete payload['jobTitle'];
    if (!payload['employmentType']) delete payload['employmentType'];
    if (!payload['specialization']) delete payload['specialization'];
    if (!payload['licenseNumber']?.trim()) delete payload['licenseNumber'];
    if (!payload['departmentId']) delete payload['departmentId'];
    if (!payload['password']?.trim()) delete payload['password'];
    if (!payload['phoneNumber']?.trim()) delete payload['phoneNumber'];

    const op = existing
      ? this.userService.update(existing.id, payload as Partial<AdminRegisterRequest>)
      : this.userService.adminRegister(payload as AdminRegisterRequest);

    op.subscribe({
      next: () => {
        this.toast.success(existing ? 'User updated' : 'User created successfully.');
        this.showCreate.set(false);
        this.saving.set(false);
        this.editing.set(null);
        this.fieldErrors.set({});
        this.loadUsers();
      },
      error: (err) => {
        this.saving.set(false);
        const body = err?.error;
        const status = err?.status;

        // 409 Conflict — duplicate field detected by the backend
        if (status === 409 && body?.field) {
          this.fieldErrors.update((prev) => ({
            ...prev,
            [body.field]: body.message ?? 'Already in use',
          }));
          this.toast.error(
            body.message ?? 'A conflict was detected. Please review the highlighted fields.',
          );
          return;
        }

        // 400 with fieldErrors map (from @Valid / Bean Validation)
        if (status === 400 && body?.fieldErrors) {
          this.fieldErrors.set(body.fieldErrors as Record<string, string>);
          this.toast.error('Please fix the highlighted fields and try again.');
          return;
        }

        // Generic fallback
        this.toast.error(body?.message ?? 'Operation failed. Please try again.');
      },
    });
  }

  /** Call from (ngModelChange) on each input to clear its per-field error immediately. */
  clearFieldError(field: string): void {
    const current = this.fieldErrors();
    if (current[field]) {
      const updated = { ...current };
      delete updated[field];
      this.fieldErrors.set(updated);
    }
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

  restoreUser(user: UserSummary): void {
    this.restoring.set(true);
    this.userService.restore(user.id).subscribe({
      next: () => {
        this.toast.success(`${user.firstName} ${user.lastName} has been restored.`);
        this.restoring.set(false);
        this.loadUsers(this.currentPage());
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to restore user');
        this.restoring.set(false);
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
