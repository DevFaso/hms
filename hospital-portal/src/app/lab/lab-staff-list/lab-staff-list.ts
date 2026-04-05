import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { StaffService, StaffResponse } from '../../services/staff.service';
import { AuthService } from '../../auth/auth.service';
import { ToastService } from '../../core/toast.service';

/** Lab roles that can be assigned via the role-update endpoint. */
const ASSIGNABLE_LAB_ROLES = [
  { code: 'ROLE_LAB_TECHNICIAN', label: 'Lab Technician' },
  { code: 'ROLE_LAB_SCIENTIST', label: 'Lab Scientist' },
  { code: 'ROLE_LAB_MANAGER', label: 'Lab Manager' },
];

@Component({
  selector: 'app-lab-staff-list',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './lab-staff-list.html',
  styleUrl: './lab-staff-list.scss',
})
export class LabStaffListComponent implements OnInit {
  private readonly staffService = inject(StaffService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  error = signal<string | null>(null);
  staff = signal<StaffResponse[]>([]);
  totalElements = signal(0);

  /** Staff member currently being edited (null = none). */
  editingId = signal<string | null>(null);
  editRoleCode = signal('');
  saving = signal(false);

  /** Whether current user can change roles (Director / Hospital Admin / Super Admin). */
  canEditRoles = computed(() => {
    const roles = this.auth.getRoles();
    return (
      roles.includes('ROLE_SUPER_ADMIN') ||
      roles.includes('ROLE_HOSPITAL_ADMIN') ||
      roles.includes('ROLE_LAB_DIRECTOR')
    );
  });

  readonly assignableRoles = ASSIGNABLE_LAB_ROLES;

  ngOnInit(): void {
    this.loadLabStaff();
  }

  loadLabStaff(): void {
    const hospitalId = this.auth.getHospitalId();
    if (!hospitalId) {
      this.error.set('No hospital context found. Please select a hospital.');
      this.loading.set(false);
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.staffService.getLabStaff(hospitalId).subscribe({
      next: (page) => {
        this.staff.set(page.content ?? []);
        this.totalElements.set(page.totalElements ?? 0);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load lab staff', err);
        this.error.set('Failed to load lab staff. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Open inline role editor for the given staff member. */
  startEdit(member: StaffResponse): void {
    this.editingId.set(member.id);
    this.editRoleCode.set(member.roleCode ?? '');
  }

  /** Cancel inline editing. */
  cancelEdit(): void {
    this.editingId.set(null);
    this.editRoleCode.set('');
  }

  /** Save the role change. */
  saveRole(member: StaffResponse): void {
    const newCode = this.editRoleCode();
    if (!newCode || newCode === member.roleCode) {
      this.cancelEdit();
      return;
    }

    this.saving.set(true);

    this.staffService.updateLabStaffRole(member.id, newCode).subscribe({
      next: (updated) => {
        // Update the local list in-place
        this.staff.update((list) =>
          list.map((s) => (s.id === updated.id ? updated : s)),
        );
        this.toast.success(`Role updated to ${updated.roleName ?? newCode}`);
        this.saving.set(false);
        this.cancelEdit();
      },
      error: (err) => {
        console.error('Failed to update role', err);
        this.toast.error(err?.error?.message ?? 'Failed to update role. Please try again.');
        this.saving.set(false);
      },
    });
  }

  /** Human-friendly role label. */
  roleBadgeClass(roleCode?: string): string {
    switch (roleCode) {
      case 'ROLE_LAB_DIRECTOR':
        return 'badge-director';
      case 'ROLE_LAB_MANAGER':
        return 'badge-manager';
      case 'ROLE_LAB_SCIENTIST':
        return 'badge-scientist';
      case 'ROLE_LAB_TECHNICIAN':
        return 'badge-technician';
      default:
        return '';
    }
  }

  /** Whether the given staff can have their role edited. Directors cannot. */
  isEditable(member: StaffResponse): boolean {
    return member.roleCode !== 'ROLE_LAB_DIRECTOR';
  }
}
