import {
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import {
  RoleService,
  RoleResponse,
  RoleCreateRequest,
  PermissionResponse,
} from '../services/role.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { roleDescriptionKey } from './role-description';
import { EnumLabelService } from '../core/enum-label.service';
import { bareRole } from '../core/role-token';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
@Component({
  selector: 'app-role-list',
  standalone: true,
  imports: [SubmitGuardDirective, FormsModule, TranslateModule, ModalDialogDirective],
  templateUrl: './role-list.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './role-list.scss',
})
export class RoleListComponent implements OnInit {
  private readonly roleService = inject(RoleService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly enumLabels = inject(EnumLabelService);

  /**
   * QA D868: the card title in the reader's language. A system role's name is
   * its code (ROLE_DOCTOR), so the card read "ROLE_DOCTOR ROLE_DOCTOR"; it is
   * shown through the role label. A custom role keeps the name its author typed.
   *
   * Whether a label exists is decided by the KEY, not by comparing the label
   * with a prettified code: in English the real labels are often exactly that
   * ('Doctor', 'Nurse'), and the comparison sent the English card back to
   * 'ROLE_DOCTOR'.
   */
  roleTitle(role: RoleResponse): string {
    const code = bareRole(role.code || role.authority || role.name);
    if (!code || !/^[A-Z][A-Z0-9_]*$/.test(code)) return role.name;
    const key = `PORTAL.ENUM.ROLE.${code}`;
    const label: unknown = this.translate.instant(key);
    if (typeof label === 'string' && label && label !== key) return label;
    // No label in the bundle: a name that is only the code is still a code,
    // never what an author typed, so it gets the readable fallback.
    const name = role.name?.trim() ?? '';
    if (!name || bareRole(name) === code) return this.enumLabels.transform(code, 'role');
    return role.name;
  }

  roles = signal<RoleResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  filtered = signal<RoleResponse[]>([]);

  // Create / Edit modal
  showCreate = signal(false);
  saving = signal(false);
  editing = signal<RoleResponse | null>(null);
  createForm: RoleCreateRequest = { name: '' };
  /** The role being edited is built in: its code field is read-only. */
  readonly codeLocked = computed(() => this.editing()?.builtIn === true);

  // Delete
  showDeleteConfirm = signal(false);
  deletingRole = signal<RoleResponse | null>(null);
  deleting = signal(false);

  // Permissions modal
  showPermissions = signal(false);
  selectedRole = signal<RoleResponse | null>(null);
  allPermissions = signal<PermissionResponse[]>([]);
  selectedPermissionIds = signal<Set<string>>(new Set());
  permissionsLoading = signal(false);

  /** QA D434: a seeded description in the reader's language, else `null`. */
  readonly descriptionKey = roleDescriptionKey;

  ngOnInit(): void {
    this.loadRoles();
  }

  loadRoles(): void {
    this.loading.set(true);
    this.roleService.list().subscribe({
      next: (list) => {
        this.roles.set(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('ROLES.TOAST.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.roles());
      return;
    }
    this.filtered.set(
      this.roles().filter(
        (r) =>
          r.name.toLowerCase().includes(term) ||
          r.authority.toLowerCase().includes(term) ||
          (r.description?.toLowerCase().includes(term) ?? false),
      ),
    );
  }

  openCreate(): void {
    this.createForm = { name: '' };
    this.editing.set(null);
    this.showCreate.set(true);
  }

  openEdit(role: RoleResponse): void {
    this.editing.set(role);
    this.createForm = {
      name: role.name,
      description: role.description ?? '',
      code: role.code ?? '',
    };
    this.showCreate.set(true);
  }

  closeCreate(): void {
    this.showCreate.set(false);
    this.editing.set(null);
  }

  submitCreate(): void {
    if (!this.createForm.name) {
      this.toast.error(this.translate.instant('ROLES.TOAST.NAME_REQUIRED'));
      return;
    }
    this.saving.set(true);
    const existing = this.editing();
    // A built-in role always sends back its stored code, whatever the field holds.
    const payload: RoleCreateRequest =
      existing?.builtIn === true ? { ...this.createForm, code: existing.code } : this.createForm;
    const op = existing
      ? this.roleService.update(existing.id, payload)
      : this.roleService.create(this.createForm);

    op.subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant(existing ? 'ROLES.TOAST.UPDATED' : 'ROLES.TOAST.CREATED'),
        );
        this.showCreate.set(false);
        this.saving.set(false);
        this.editing.set(null);
        this.loadRoles();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ROLES.TOAST.OPERATION_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  confirmDelete(role: RoleResponse): void {
    // A built-in role is never deleted (the server answers 409); no trash icon offers it.
    if (role.builtIn) return;
    this.deletingRole.set(role);
    this.showDeleteConfirm.set(true);
  }

  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingRole.set(null);
  }

  executeDelete(): void {
    const role = this.deletingRole();
    if (!role) return;
    this.deleting.set(true);
    this.roleService.delete(role.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ROLES.TOAST.DELETED'));
        this.showDeleteConfirm.set(false);
        this.deleting.set(false);
        this.deletingRole.set(null);
        this.loadRoles();
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('ROLES.TOAST.DELETE_FAILED'),
        );
        this.deleting.set(false);
      },
    });
  }

  openPermissions(role: RoleResponse): void {
    this.selectedRole.set(role);
    this.selectedPermissionIds.set(new Set(role.permissions.map((p) => p.id)));
    this.showPermissions.set(true);

    if (this.allPermissions().length === 0) {
      this.permissionsLoading.set(true);
      this.roleService.listPermissions().subscribe({
        next: (perms) => {
          this.allPermissions.set(perms);
          this.permissionsLoading.set(false);
        },
        error: () => {
          this.toast.error(this.translate.instant('ROLES.TOAST.PERMS_LOAD_FAILED'));
          this.permissionsLoading.set(false);
        },
      });
    }
  }

  closePermissions(): void {
    this.showPermissions.set(false);
  }

  togglePermission(id: string): void {
    this.selectedPermissionIds.update((set) => {
      const newSet = new Set(set);
      if (newSet.has(id)) {
        newSet.delete(id);
      } else {
        newSet.add(id);
      }
      return newSet;
    });
  }

  savePermissions(): void {
    const role = this.selectedRole();
    if (!role) return;
    this.saving.set(true);
    this.roleService
      .assignPermissions(role.id, Array.from(this.selectedPermissionIds()))
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('ROLES.TOAST.PERMS_UPDATED'));
          this.showPermissions.set(false);
          this.saving.set(false);
          this.loadRoles();
        },
        error: () => {
          this.toast.error(this.translate.instant('ROLES.TOAST.PERMS_UPDATE_FAILED'));
          this.saving.set(false);
        },
      });
  }
}
