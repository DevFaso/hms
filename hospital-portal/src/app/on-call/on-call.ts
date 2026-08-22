import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  DepartmentOption,
  OnCallScheduleRequest,
  OnCallScheduleResponse,
  OnCallService,
} from '../services/on-call.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

/** Form model: datetime-local strings, converted to ISO with offset on submit. */
interface OnCallFormModel {
  staffId: string;
  departmentId: string;
  startLocal: string;
  endLocal: string;
  notes: string;
}

/**
 * On-call rota (P2 #13).
 *
 * This page is the first writer for clinical.on_call_schedules — the table
 * behind the dashboard's on-call pill, which could only ever say "Off duty"
 * because nothing wrote rota entries. Reads span six roles; writes are
 * HOSPITAL_ADMIN / SUPER_ADMIN only (mirroring the backend split), so the
 * write controls are gated in-component rather than at the route.
 */
@Component({
  selector: 'app-on-call',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './on-call.html',
  styleUrl: './on-call.scss',
})
export class OnCallComponent implements OnInit {
  private readonly onCallService = inject(OnCallService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);

  entries = signal<OnCallScheduleResponse[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);

  staffOptions = signal<StaffResponse[]>([]);
  departments = signal<DepartmentOption[]>([]);

  /** Rota window filter (date-only; server default is now-1d → now+7d). */
  filterFrom = '';
  filterTo = '';

  /** Mirrors OnCallScheduleController.WRITE_ROLES. */
  canManage = computed(() =>
    this.roleContext.hasAnyActiveRole(['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN']),
  );

  modalOpen = signal(false);
  editingId = signal<string | null>(null);
  saving = signal(false);
  form: OnCallFormModel = this.emptyForm();

  deleteTarget = signal<OnCallScheduleResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    const from = this.filterFrom
      ? new Date(`${this.filterFrom}T00:00:00`).toISOString()
      : undefined;
    const to = this.filterTo ? new Date(`${this.filterTo}T23:59:59`).toISOString() : undefined;
    this.onCallService.list(from, to).subscribe({
      next: (list) => {
        this.entries.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        // "The rota window ends before it starts." is actionable; keep it.
        this.error.set(err?.error?.message ?? this.translate.instant('ON_CALL.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editingId.set(null);
    this.ensureFormOptions();
    this.modalOpen.set(true);
  }

  openEdit(entry: OnCallScheduleResponse): void {
    this.form = {
      staffId: entry.staffId,
      departmentId: entry.departmentId ?? '',
      startLocal: this.toLocalInput(entry.startTime),
      endLocal: this.toLocalInput(entry.endTime),
      notes: entry.notes ?? '',
    };
    this.editingId.set(entry.id);
    this.ensureFormOptions();
    this.modalOpen.set(true);
  }

  closeModal(): void {
    this.modalOpen.set(false);
    this.editingId.set(null);
    this.form = this.emptyForm();
  }

  submit(): void {
    if (!this.form.staffId) {
      this.toast.error(this.translate.instant('ON_CALL.STAFF_REQUIRED'));
      return;
    }
    if (!this.form.startLocal || !this.form.endLocal) {
      this.toast.error(this.translate.instant('ON_CALL.WINDOW_REQUIRED'));
      return;
    }
    // Strict: the backend rejects a zero-length shift too.
    if (new Date(this.form.endLocal) <= new Date(this.form.startLocal)) {
      this.toast.error(this.translate.instant('ON_CALL.WINDOW_INVALID'));
      return;
    }

    const request: OnCallScheduleRequest = {
      staffId: this.form.staffId,
      departmentId: this.form.departmentId || undefined,
      startTime: new Date(this.form.startLocal).toISOString(),
      endTime: new Date(this.form.endLocal).toISOString(),
      notes: this.form.notes || undefined,
    };

    this.saving.set(true);
    const id = this.editingId();
    const request$ = id
      ? this.onCallService.update(id, request)
      : this.onCallService.create(request);

    request$.subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ON_CALL.SAVED'));
        this.saving.set(false);
        this.closeModal();
        this.load();
      },
      error: (err) => {
        // The overlap refusal ("already on call over part of that window") is
        // the whole point of the backend rule — surface it verbatim, never a
        // generic save-failed toast.
        this.toast.error(err?.error?.message ?? this.translate.instant('ON_CALL.SAVE_ERROR'));
        this.saving.set(false);
      },
    });
  }

  confirmDelete(entry: OnCallScheduleResponse): void {
    this.deleteTarget.set(entry);
  }

  cancelDelete(): void {
    this.deleteTarget.set(null);
  }

  executeDelete(): void {
    const target = this.deleteTarget();
    if (!target || this.deleting()) {
      return;
    }
    this.deleting.set(true);
    this.onCallService.delete(target.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ON_CALL.DELETED'));
        this.deleting.set(false);
        this.deleteTarget.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? this.translate.instant('ON_CALL.DELETE_ERROR'));
        this.deleting.set(false);
      },
    });
  }

  /** Staff + department options load once, on first modal open. */
  private ensureFormOptions(): void {
    if (this.staffOptions().length === 0) {
      const hospitalId =
        this.roleContext.activeHospitalId ?? this.auth.getHospitalId() ?? undefined;
      this.staffService.list(hospitalId ?? undefined).subscribe({
        next: (staff) => this.staffOptions.set(staff),
        error: () => this.toast.error(this.translate.instant('ON_CALL.STAFF_LOAD_ERROR')),
      });
    }
    if (this.departments().length === 0) {
      this.onCallService.listDepartments().subscribe({
        next: (deps) => this.departments.set(deps),
        // Department scope is optional — the form still works hospital-wide.
        error: () => this.departments.set([]),
      });
    }
  }

  /** ISO (with offset) → the local "YYYY-MM-DDTHH:mm" a datetime-local input needs. */
  private toLocalInput(iso: string): string {
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  private emptyForm(): OnCallFormModel {
    return { staffId: '', departmentId: '', startLocal: '', endLocal: '', notes: '' };
  }
}
