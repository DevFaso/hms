import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  RoleService,
  RoleResponse,
  RoleCreateRequest,
  PermissionResponse,
} from '../services/role.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-role-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './role-list.html',
  styleUrl: './role-list.scss',
})
export class RoleListComponent implements OnInit {
  private readonly roleService = inject(RoleService);
  private readonly toast = inject(ToastService);

  roles = signal<RoleResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  filtered = signal<RoleResponse[]>([]);

  // Create modal
  showCreate = signal(false);
  saving = signal(false);
  createForm: RoleCreateRequest = { name: '' };

  // Permissions modal
  showPermissions = signal(false);
  selectedRole = signal<RoleResponse | null>(null);
  allPermissions = signal<PermissionResponse[]>([]);
  selectedPermissionIds = signal<Set<string>>(new Set());
  permissionsLoading = signal(false);

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
        this.toast.error('Failed to load roles');
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
    this.showCreate.set(true);
  }

  closeCreate(): void {
    this.showCreate.set(false);
  }

  submitCreate(): void {
    if (!this.createForm.name) {
      this.toast.error('Role name is required');
      return;
    }
    this.saving.set(true);
    this.roleService.create(this.createForm).subscribe({
      next: () => {
        this.toast.success('Role created');
        this.showCreate.set(false);
        this.saving.set(false);
        this.loadRoles();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to create role');
        this.saving.set(false);
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
          this.toast.error('Failed to load permissions');
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
          this.toast.success('Permissions updated');
          this.showPermissions.set(false);
          this.saving.set(false);
          this.loadRoles();
        },
        error: () => {
          this.toast.error('Failed to update permissions');
          this.saving.set(false);
        },
      });
  }
}
