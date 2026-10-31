import { DialogFocusDirective } from '../shared/a11y/dialog-focus.directive';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  inject,
  OnDestroy,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { UserService, UserSummary, AdminRegisterRequest } from '../services/user.service';
import { grantableRoles } from '../core/grantable-roles';
import { RoleService, RoleResponse } from '../services/role.service';
import {
  HospitalService,
  HospitalResponse,
  isOpenForAssignment,
} from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { deliveryWarningKeys, hasActivationSent } from '../shared/delivery-warnings';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { ImpersonationService } from '../services/impersonation.service';
import { Router } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { isStepUpRefusal, stepUpRefusalKey } from '../services/step-up';

const PATIENT_ROLE_CODES = new Set(['ROLE_PATIENT', 'PATIENT']);

/** Local (70202020) or international (+226 70 20 20 20 / 00226...) - the server normalises to E.164. */
const PHONE_PATTERN = /^\+?[\d\s.()-]{8,20}$/;

const MEDICAL_ROLE_CODES = new Set([
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_LAB_SCIENTIST',
  'ROLE_PHARMACIST',
]);

/** Roles whose holders have a medical specialty (QA D195): not nurses, pharmacists or lab staff. */
const SPECIALTY_ROLE_CODES = new Set([
  'ROLE_DOCTOR',
  'ROLE_PHYSICIAN',
  'ROLE_SURGEON',
  'ROLE_RADIOLOGIST',
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
  'LAB_DIRECTOR',
  'QUALITY_MANAGER',
  'RECEPTIONIST',
  'SURGEON',
  // QA D541: HOSPITAL_ADMINISTRATOR (legacy) is offered once, as HOSPITAL_ADMIN.
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
  imports: [DialogFocusDirective, FormsModule, TranslateModule, RoleLabelPipe, EnumLabelPipe],
  templateUrl: './user-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './user-list.scss',
})
export class UserListComponent implements OnInit, OnDestroy {
  private readonly userService = inject(UserService);
  private readonly roleService = inject(RoleService);
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly impersonation = inject(ImpersonationService);
  private readonly router = inject(Router);
  private readonly translate = inject(TranslateService);

  private readonly searchSubject = new Subject<string>();
  private readonly destroy$ = new Subject<void>();

  users = signal<UserSummary[]>([]);
  filtered = signal<UserSummary[]>([]);
  loading = signal(true);
  searchTerm = '';

  /* Filter state */
  roleFilter = '';
  statusFilter = '';

  /**
   * QA D919: the deleted view and the restore are the super-admin's (the
   * server answers anyone else an empty worklist, and 404 on a deleted
   * account), so nobody else is offered the "Deleted" filter or told an
   * account can be restored from it.
   */
  canSeeDeleted(): boolean {
    return this.roleContext.isSuperAdmin();
  }
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

  // MVP-4: support impersonation modal state
  impersonateTarget = signal<UserSummary | null>(null);
  impersonateReason = '';
  impersonateMfaCode = '';
  impersonating = signal(false);
  impersonateError = signal<string | null>(null);

  /** Per-field validation error messages (from 400/409 backend responses). */
  fieldErrors = signal<Record<string, string>>({});

  // Lookup data for dropdowns
  availableRoles = signal<RoleResponse[]>([]);

  /**
   * The roles the create modal offers (QA D189). Only a super-admin grants an
   * admin role (the server refuses anyone else with a 403), and a patient is
   * registered from the patient form, not here, so a hospital admin is
   * offered staff roles only.
   */
  // QA D189/D626: the shared grant rule (no patient role, no admin role below super-admin).
  readonly grantableRoles = computed(() =>
    grantableRoles(this.availableRoles(), this.roleContext.isSuperAdmin()),
  );
  availableHospitals = signal<{ id: string; name: string }[]>([]);

  // Form fields
  createForm: AdminRegisterRequest = this.freshForm();
  selectedRoles: string[] = [];

  // Enum options for dropdowns
  readonly jobTitles = JOB_TITLES;
  readonly employmentTypes = EMPLOYMENT_TYPES;
  readonly specializations = SPECIALIZATIONS;

  /** One-time credentials the server generated and could not deliver; shown once. */
  issuedCredentials = signal<{ username: string; password: string } | null>(null);

  /**
   * True when a selected role is a regulated profession: the licence field is
   * shown. It is ALWAYS optional - clinicians are credentialed on diplomas.
   */
  get licenseApplicable(): boolean {
    return this.selectedRoles.some((r) => MEDICAL_ROLE_CODES.has(r));
  }

  /** True when a selected role is a physician's: only then is a medical specialization asked. */
  get specializationApplicable(): boolean {
    return this.selectedRoles.some((r) => SPECIALTY_ROLE_CODES.has(r));
  }

  /** E-mail is optional only for a patient-only registration (phone-first). */
  get emailRequired(): boolean {
    return (
      this.selectedRoles.length === 0 || this.selectedRoles.some((r) => !PATIENT_ROLE_CODES.has(r))
    );
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

    // Deleted rows are requested on this screen because it is the admin
    // surface the Restore button lives on - without them the Deleted filter
    // was a dead control while a ghost account could hold a unique email.
    // The Deleted view itself pages SERVER-side (onlyDeleted): filtering a
    // mixed 20-row page client-side could show "no matches" while the ghost
    // sat on a later page - the invisible-ghost bug wearing a new hat.
    const deletedView = this.statusFilter === 'deleted' && this.canSeeDeleted();
    const request$ = hasServerFilter
      ? this.userService.search(page, 20, {
          ...(this.roleFilter ? { role: this.roleFilter } : {}),
          ...(term ? { name: term } : {}),
          includeDeleted: this.canSeeDeleted(),
          ...(deletedView ? { onlyDeleted: true } : {}),
        })
      : this.userService.list(page, 20, this.canSeeDeleted(), deletedView);

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
        this.toast.error(this.translate.instant('USERS.TOAST.LOAD_FAILED'));
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
        // QA D818: a new user's role is never granted at an archived hospital.
        next: (hospitals: HospitalResponse[]) =>
          this.availableHospitals.set(
            hospitals.filter(isOpenForAssignment).map((h) => ({ id: h.id, name: h.name })),
          ),
        error: () => {
          /* silent */
        },
      });
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.availableHospitals.set([{ id: h.id, name: h.name }]);
          this.createForm.hospitalId = h.id;
        },
      });
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
    }
    // 'deleted' needs no client-side filtering: the page itself is the
    // server-side ghost worklist (onlyDeleted).

    this.filtered.set(result);
  }

  /** Called when the role filter dropdown changes — reloads from backend page 0. */
  onRoleFilterChange(): void {
    this.currentPage.set(0);
    this.loadUsers(0);
  }

  /** Called when the status filter dropdown changes — client-side only re-filter. */
  onStatusFilterChange(): void {
    // The Deleted view is a different server-side query, so entering or
    // leaving it restarts at page 0; active/inactive remain client-side
    // refinements of the loaded page.
    this.loadUsers(0);
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
    // A hospital admin onboards into their own hospital: freshForm() has no
    // hospitalId, so re-apply the locked hospital or the request goes out
    // without one (D201).
    if (!this.roleContext.isSuperAdmin()) {
      const own = this.availableHospitals();
      if (own.length === 1) this.createForm.hospitalId = own[0].id;
    }
    this.selectedRoles = [];
    this.editing.set(null);
    this.fieldErrors.set({});
    this.showCreate.set(true);
  }

  /** Every role of the account being edited, shown read-only (QA D765). */
  readonly editRoles = signal<string[]>([]);

  openEdit(user: UserSummary): void {
    // A deleted account is restored first; the server refuses the edit (D126).
    if (user.deleted) return;
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
    // QA D765: the summary carries the primary role only. Show every role the
    // account holds, read-only: roles are granted per hospital from the role
    // assignments page, and PUT /users/{id} never changes them.
    this.editRoles.set(user.roleName ? [user.roleName] : []);
    this.editStaffHospitalName.set(null);
    this.fieldErrors.set({});
    this.showCreate.set(true);

    // Fetch full user detail to populate phoneNumber and other fields
    // not available in the summary projection.
    this.userService.getById(user.id).subscribe({
      next: (detail) => {
        if (detail.phoneNumber) {
          this.createForm.phoneNumber = detail.phoneNumber;
        }
        const codes = roleCodesOf(detail.roles);
        if (codes.length > 0) {
          this.editRoles.set(codes);
          this.selectedRoles = codes;
        }
        // QA D838: the staff profile's hospital, job title and employment
        // type (and specialization, licence) are shown as registered, so an
        // admin editing only the phone does not read them as lost. They are
        // read-only here: PUT /users does not change them.
        this.createForm.hospitalId = detail.staffHospitalId ?? '';
        this.createForm.jobTitle = detail.jobTitle ?? '';
        this.createForm.employmentType = detail.employmentType ?? '';
        this.createForm.specialization = detail.specialization ?? undefined;
        this.createForm.licenseNumber = detail.licenseNumber ?? undefined;
        this.editStaffHospitalName.set(detail.staffHospitalName ?? null);
      },
    });
  }

  /** QA D838: the edited account's staff hospital, shown read-only. */
  readonly editStaffHospitalName = signal<string | null>(null);

  dismissCredentials(): void {
    this.issuedCredentials.set(null);
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
    if (!this.licenseApplicable) {
      this.createForm.licenseNumber = undefined;
    }
    if (!this.specializationApplicable) {
      this.createForm.specialization = undefined;
    }
  }

  submitCreate(): void {
    const isEdit = !!this.editing();
    // Username and password are never asked on create: the server generates
    // one-time credentials. Edits keep the username field.
    if (
      (isEdit && !this.createForm.username) ||
      (this.emailRequired && !this.createForm.email?.trim()) ||
      (!isEdit && !this.createForm.phoneNumber?.trim()) ||
      !this.createForm.firstName ||
      !this.createForm.lastName
    ) {
      this.toast.error(this.translate.instant('USERS.TOAST.REQUIRED_FIELDS'));
      return;
    }
    if (!isEdit && this.selectedRoles.length === 0) {
      this.toast.error(this.translate.instant('USERS.TOAST.ROLE_REQUIRED'));
      return;
    }
    const phone = this.createForm.phoneNumber?.trim();
    if (phone && !PHONE_PATTERN.test(phone)) {
      this.fieldErrors.update((prev) => ({
        ...prev,
        phoneNumber: this.translate.instant('USERS.PHONE_INVALID'),
      }));
      this.toast.error(this.translate.instant('USERS.PHONE_INVALID'));
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
    const payload: Partial<AdminRegisterRequest> = { ...this.createForm };
    if (!payload.hospitalId) delete payload.hospitalId;
    if (!payload.jobTitle) delete payload.jobTitle;
    if (!payload.employmentType) delete payload.employmentType;
    if (!payload.specialization) delete payload.specialization;
    if (!payload.licenseNumber?.trim()) delete payload.licenseNumber;
    if (!payload.departmentId) delete payload.departmentId;
    if (!payload.password?.trim()) delete payload.password;
    if (!payload.phoneNumber?.trim()) delete payload.phoneNumber;
    if (!payload.email?.trim()) delete payload.email;
    // QA D765: an edit never sends roles; the server refuses a role change here.
    if (existing) delete payload.roleNames;
    // QA D838: nor the staff profile fields it shows read-only.
    if (existing) {
      delete payload.hospitalId;
      delete payload.jobTitle;
      delete payload.employmentType;
      delete payload.specialization;
      delete payload.licenseNumber;
      delete payload.departmentId;
    }
    if (!existing) {
      delete payload.username;
      delete payload.password;
    }

    const op = existing
      ? this.userService.update(existing.id, payload)
      : this.userService.adminRegister(payload as AdminRegisterRequest);

    op.subscribe({
      next: (saved) => {
        this.toast.success(
          this.translate.instant(existing ? 'USERS.TOAST.UPDATED' : 'USERS.TOAST.CREATED'),
        );
        // The account exists either way — but a dead mail/SMS transport used
        // to hide behind this very success toast. Warn the registrar so they
        // know to use "Resend code" once the transport is fixed.
        if (!existing) {
          for (const key of deliveryWarningKeys(saved.activationDelivery)) {
            this.toast.warning(this.translate.instant(key));
          }
          // QA D522: say so when the activation went out by e-mail, so a failed
          // SMS next to it does not read as "activation by SMS only".
          if (hasActivationSent(saved.activationDelivery, 'EMAIL')) {
            this.toast.info(this.translate.instant('DELIVERY.ACTIVATION_EMAIL_QUEUED'));
          }
          if (saved.temporaryUsername && saved.temporaryPassword) {
            this.issuedCredentials.set({
              username: saved.temporaryUsername,
              password: saved.temporaryPassword,
            });
          }
        }
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
          this.toast.error(body.message ?? this.translate.instant('USERS.TOAST.CONFLICT'));
          return;
        }

        // 400 with fieldErrors map (from @Valid / Bean Validation)
        if (status === 400 && body?.fieldErrors) {
          this.fieldErrors.set(body.fieldErrors as Record<string, string>);
          this.toast.error(this.translate.instant('USERS.TOAST.VALIDATION_ERRORS'));
          return;
        }

        // Generic fallback
        this.toast.error(body?.message ?? this.translate.instant('USERS.TOAST.OPERATION_FAILED'));
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
        this.toast.success(this.translate.instant('USERS.TOAST.DELETED'));
        this.showDeleteConfirm.set(false);
        this.deleting.set(false);
        this.deletingUser.set(null);
        this.loadUsers();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('USERS.TOAST.DELETE_FAILED'),
        );
        this.deleting.set(false);
      },
    });
  }

  restoreUser(user: UserSummary): void {
    this.restoring.set(true);
    this.userService.restore(user.id).subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant('USERS.TOAST.RESTORED', {
            name: `${user.firstName} ${user.lastName}`,
          }),
        );
        this.restoring.set(false);
        this.loadUsers(this.currentPage());
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('USERS.TOAST.RESTORE_FAILED'),
        );
        this.restoring.set(false);
      },
    });
  }

  // ── MVP-4: support impersonation ────────────────────────────────────
  /** True when the current user is a super admin and the row is impersonable. */
  /** QA D902: the signed-in account is never offered its own delete (the server refuses it). */
  isSelf(user: UserSummary): boolean {
    const me = this.auth.getUserProfile()?.id ?? this.auth.getUserId();
    return !!me && user.id === me;
  }

  /**
   * QA D914: an account a hospital admin may not administer (it is also
   * managed at another hospital, or holds an admin role) offers no edit or
   * delete; saving it answered "user not found".
   */
  isManageable(user: UserSummary): boolean {
    return user.manageable !== false;
  }

  canImpersonate(user: UserSummary): boolean {
    if (!this.roleContext.isSuperAdmin()) return false;
    if (user.deleted || !user.active) return false;
    const role = (user.roleName ?? '').toUpperCase();
    if (role === 'SUPER_ADMIN' || role === 'ROLE_SUPER_ADMIN') return false;
    if (this.impersonation.active()?.impersonating) return false;
    return true;
  }

  openImpersonate(user: UserSummary): void {
    this.impersonateTarget.set(user);
    this.impersonateReason = '';
    this.impersonateMfaCode = '';
    this.impersonateError.set(null);
  }

  closeImpersonate(): void {
    this.impersonateTarget.set(null);
    this.impersonateReason = '';
    this.impersonateMfaCode = '';
    this.impersonateError.set(null);
  }

  submitImpersonate(): void {
    const target = this.impersonateTarget();
    const reason = this.impersonateReason.trim();
    if (!target || reason.length < 5) {
      this.impersonateError.set(this.translate.instant('USERS.TOAST.IMPERSONATE_REASON_TOO_SHORT'));
      return;
    }
    this.impersonating.set(true);
    this.impersonateError.set(null);
    this.impersonation
      .start({ targetUserId: target.id, reason }, this.impersonateMfaCode.trim() || undefined)
      .subscribe({
        next: () => {
          this.impersonating.set(false);
          this.closeImpersonate();
          this.toast.success(
            this.translate.instant('USERS.TOAST.IMPERSONATE_STARTED', { name: target.username }),
          );
          this.router.navigateByUrl('/dashboard');
        },
        error: (err) => {
          this.impersonating.set(false);
          this.impersonateError.set(
            err instanceof HttpErrorResponse && isStepUpRefusal(err)
              ? this.translate.instant(stepUpRefusalKey(err))
              : (err?.error?.message ?? this.translate.instant('USERS.TOAST.IMPERSONATE_FAILED')),
          );
        },
      });
  }

  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.loadUsers(page);
    }
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

/**
 * The role codes of a user detail. The API sends role summaries
 * ({ code, name }); older callers typed them as plain strings.
 */
export function roleCodesOf(roles: unknown): string[] {
  if (!Array.isArray(roles)) return [];
  const codes: string[] = [];
  for (const r of roles) {
    const code =
      typeof r === 'string'
        ? r
        : ((r as { code?: string; name?: string })?.code ?? (r as { name?: string })?.name);
    if (code && !codes.includes(code)) codes.push(code);
  }
  return codes;
}
