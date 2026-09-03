import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import {
  AssignmentAdminService,
  AssignmentBatchResponse,
  AssignmentBulkImportResponse,
  AssignmentResponse,
} from '../services/assignment-admin.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { RoleService, RoleResponse } from '../services/role.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { deliveryWarningKeys } from '../shared/delivery-warnings';

type ModalKind = 'create' | 'multi' | 'edit' | 'detail' | 'regen' | 'import' | null;

/**
 * Assignment administration (HOSPITAL_ADMIN / SUPER_ADMIN — enforced by a
 * SecurityConfig URL matcher on /assignments/**).
 * ⚠ The backend applies no tenant scoping: the hospital filter is cosmetic.
 * For HOSPITAL_ADMIN users the filter defaults to their active hospital, but
 * the server would honour any hospitalId.
 */
@Component({
  selector: 'app-admin-assignments',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './admin-assignments.html',
  styleUrl: './admin-assignments.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminAssignmentsComponent implements OnInit {
  private readonly service = inject(AssignmentAdminService);
  private readonly hospitalService = inject(HospitalService);
  private readonly roleService = inject(RoleService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly route = inject(ActivatedRoute);

  /* List state */
  rows = signal<AssignmentResponse[]>([]);
  loading = signal(false);
  loadError = signal(false);
  page = signal(0);
  totalPages = signal(0);
  totalElements = signal(0);
  readonly pageSize = 20;

  /* Filters */
  filterSearch = signal('');
  filterHospitalId = signal('');
  filterActive = signal('');
  filterCode = signal('');

  /* Reference data */
  hospitals = signal<HospitalResponse[]>([]);
  roles = signal<RoleResponse[]>([]);

  /* Modals */
  modal = signal<ModalKind>(null);
  selected = signal<AssignmentResponse | null>(null);
  saving = signal(false);

  /* Create / edit form */
  formUserIdentifier = signal('');
  formRoleId = signal('');
  formHospitalId = signal('');
  formStartDate = signal('');
  formActive = signal(true);

  /* Multi-scope form */
  multiHospitalIds = signal<Set<string>>(new Set());
  multiSendNotifications = signal(true);
  multiSkipConflicts = signal(true);
  multiResult = signal<AssignmentBatchResponse | null>(null);

  /* Regenerate form */
  regenResend = signal(true);

  /* Bulk import form */
  importCsv = signal('');
  importFileName = signal('');
  importDelimiter = signal(',');
  importDefaultRoleId = signal('');
  importDefaultHospitalId = signal('');
  importDefaultActive = signal(true);
  importSendNotifications = signal(false);
  importSkipConflicts = signal(true);
  importResult = signal<AssignmentBulkImportResponse | null>(null);

  ngOnInit(): void {
    // A hospital admin most often wants their own hospital's assignments;
    // pre-filter to the active scope (still just a filter server-side).
    if (!this.roleContext.hasAnyActiveRole(['ROLE_SUPER_ADMIN'])) {
      this.filterHospitalId.set(this.roleContext.activeHospitalId ?? '');
    }

    // `?confirm=<assignmentCode>` is the assigner-confirmation link from
    // AssignmentLinkService. It used to point at /super/assignments, a route
    // that does not exist in this app, so the link went nowhere. Landing on
    // the code-filtered list puts the assigner on the one row they were
    // emailed about; the confirmation code itself is theirs to enter, and
    // the hospital pre-filter is cleared so a code from another scope is
    // still found.
    const confirmCode = this.route.snapshot.queryParamMap.get('confirm')?.trim();
    if (confirmCode) {
      this.filterCode.set(confirmCode);
      this.filterHospitalId.set('');
    }

    this.load();
    this.hospitalService.list().subscribe({
      next: (h) => this.hospitals.set(h),
      error: () => this.toast.error(this.translate.instant('ASSIGN_ADMIN.HOSPITALS_LOAD_ERROR')),
    });
    this.roleService.list().subscribe({
      next: (r) => this.roles.set(r),
      error: () => this.toast.error(this.translate.instant('ASSIGN_ADMIN.ROLES_LOAD_ERROR')),
    });
  }

  load(page = 0): void {
    this.loading.set(true);
    this.loadError.set(false);
    const active = this.filterActive();
    this.service
      .list(page, this.pageSize, {
        hospitalId: this.filterHospitalId() || undefined,
        active: active === '' ? undefined : active === 'true',
        search: this.filterSearch().trim() || undefined,
        assignmentCode: this.filterCode().trim() || undefined,
      })
      .subscribe({
        next: (res) => {
          this.rows.set(res.content ?? []);
          this.page.set(res.number ?? page);
          this.totalPages.set(res.totalPages ?? 0);
          this.totalElements.set(res.totalElements ?? 0);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.loadError.set(true);
        },
      });
  }

  applyFilters(): void {
    this.load(0);
  }

  clearFilters(): void {
    this.filterSearch.set('');
    this.filterHospitalId.set('');
    this.filterActive.set('');
    this.filterCode.set('');
    this.load(0);
  }

  /* ── Modals ── */

  openCreate(): void {
    this.formUserIdentifier.set('');
    this.formRoleId.set('');
    this.formHospitalId.set(this.filterHospitalId());
    this.formStartDate.set('');
    this.formActive.set(true);
    this.modal.set('create');
  }

  openMulti(): void {
    this.formUserIdentifier.set('');
    this.formRoleId.set('');
    this.formStartDate.set('');
    this.multiHospitalIds.set(new Set());
    this.multiSendNotifications.set(true);
    this.multiSkipConflicts.set(true);
    this.multiResult.set(null);
    this.modal.set('multi');
  }

  openEdit(row: AssignmentResponse): void {
    this.selected.set(row);
    this.formUserIdentifier.set('');
    this.formRoleId.set(row.roleId ?? '');
    this.formHospitalId.set(row.hospitalId ?? '');
    this.formStartDate.set(row.startDate ?? '');
    this.formActive.set(row.active);
    this.modal.set('edit');
  }

  openDetail(row: AssignmentResponse): void {
    this.selected.set(row);
    this.modal.set('detail');
  }

  openRegen(row: AssignmentResponse): void {
    this.selected.set(row);
    this.regenResend.set(true);
    this.modal.set('regen');
  }

  openImport(): void {
    this.importCsv.set('');
    this.importFileName.set('');
    this.importDelimiter.set(',');
    this.importDefaultRoleId.set('');
    this.importDefaultHospitalId.set(this.filterHospitalId());
    this.importDefaultActive.set(true);
    this.importSendNotifications.set(false);
    this.importSkipConflicts.set(true);
    this.importResult.set(null);
    this.modal.set('import');
  }

  closeModal(): void {
    this.modal.set(null);
    this.selected.set(null);
  }

  /* ── Actions ── */

  submitCreate(): void {
    if (!this.formUserIdentifier().trim() || !this.formRoleId()) {
      this.toast.error(this.translate.instant('ASSIGN_ADMIN.FORM_REQUIRED'));
      return;
    }
    this.saving.set(true);
    this.service
      .create({
        userIdentifier: this.formUserIdentifier().trim(),
        roleId: this.formRoleId(),
        hospitalId: this.formHospitalId() || undefined,
        startDate: this.formStartDate() || undefined,
        active: this.formActive(),
      })
      .subscribe({
        next: (created) => {
          this.saving.set(false);
          this.toast.success(this.translate.instant('ASSIGN_ADMIN.CREATED'));
          this.rows.update((list) => [created, ...list]);
          this.closeModal();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(err.error?.message ?? this.translate.instant('ASSIGN_ADMIN.SAVE_ERROR'));
        },
      });
  }

  submitMulti(): void {
    const hospitalIds = [...this.multiHospitalIds()];
    if (!this.formUserIdentifier().trim() || !this.formRoleId() || hospitalIds.length === 0) {
      this.toast.error(this.translate.instant('ASSIGN_ADMIN.MULTI_REQUIRED'));
      return;
    }
    this.saving.set(true);
    this.service
      .createMultiScope({
        userIdentifier: this.formUserIdentifier().trim(),
        roleId: this.formRoleId(),
        hospitalIds,
        startDate: this.formStartDate() || undefined,
        sendNotifications: this.multiSendNotifications(),
        skipConflicts: this.multiSkipConflicts(),
      })
      .subscribe({
        next: (res) => {
          this.saving.set(false);
          this.multiResult.set(res);
          this.load(this.page());
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(err.error?.message ?? this.translate.instant('ASSIGN_ADMIN.SAVE_ERROR'));
        },
      });
  }

  submitEdit(): void {
    const row = this.selected();
    if (!row || !this.formRoleId()) return;
    this.saving.set(true);
    this.service
      .update(row.id, {
        userId: row.userId,
        roleId: this.formRoleId(),
        hospitalId: this.formHospitalId() || undefined,
        startDate: this.formStartDate() || undefined,
        active: this.formActive(),
      })
      .subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.toast.success(this.translate.instant('ASSIGN_ADMIN.UPDATED'));
          this.patchRow(updated);
          this.closeModal();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(err.error?.message ?? this.translate.instant('ASSIGN_ADMIN.SAVE_ERROR'));
        },
      });
  }

  submitRegen(): void {
    const row = this.selected();
    if (!row) return;
    this.saving.set(true);
    this.service.regenerateCode(row.id, this.regenResend()).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.toast.success(this.translate.instant('ASSIGN_ADMIN.REGENERATED'));
        for (const key of deliveryWarningKeys(updated.activationDelivery)) {
          this.toast.warning(this.translate.instant(key));
        }
        this.patchRow(updated);
        this.closeModal();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('ASSIGN_ADMIN.ACTION_ERROR'));
      },
    });
  }

  resendNotification(row: AssignmentResponse): void {
    this.service.resendNotification(row.id).subscribe({
      next: (report) => {
        // "Sent" only when it actually went somewhere; otherwise say why not
        // instead of the old unconditional success over a dead transport.
        const warnings = deliveryWarningKeys(report);
        if (warnings.length === 0) {
          this.toast.success(this.translate.instant('ASSIGN_ADMIN.NOTIFICATION_SENT'));
        }
        for (const key of warnings) {
          this.toast.warning(this.translate.instant(key));
        }
      },
      error: () => this.toast.error(this.translate.instant('ASSIGN_ADMIN.ACTION_ERROR')),
    });
  }

  deactivate(row: AssignmentResponse): void {
    this.service.deactivate(row.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ASSIGN_ADMIN.DEACTIVATED'));
        this.patchRow({ ...row, active: false });
      },
      error: () => this.toast.error(this.translate.instant('ASSIGN_ADMIN.ACTION_ERROR')),
    });
  }

  delete(row: AssignmentResponse): void {
    if (!confirm(this.translate.instant('ASSIGN_ADMIN.DELETE_CONFIRM'))) return;
    this.service.delete(row.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ASSIGN_ADMIN.DELETED'));
        this.rows.update((list) => list.filter((r) => r.id !== row.id));
      },
      error: (err: HttpErrorResponse) => {
        this.toast.error(
          err.status === 409
            ? this.translate.instant('ASSIGN_ADMIN.DELETE_CONFLICT')
            : this.translate.instant('ASSIGN_ADMIN.ACTION_ERROR'),
        );
      },
    });
  }

  /* ── Bulk import ── */

  onImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.importFileName.set(file.name);
    const reader = new FileReader();
    reader.onload = () => this.importCsv.set(String(reader.result ?? ''));
    reader.readAsText(file);
  }

  submitImport(): void {
    if (!this.importCsv().trim()) {
      this.toast.error(this.translate.instant('ASSIGN_ADMIN.IMPORT_FILE_REQUIRED'));
      return;
    }
    this.saving.set(true);
    this.service
      .bulkImport({
        csvContent: this.importCsv(),
        delimiter: this.importDelimiter() || undefined,
        defaultRoleId: this.importDefaultRoleId() || undefined,
        defaultHospitalId: this.importDefaultHospitalId() || undefined,
        defaultActive: this.importDefaultActive(),
        sendNotifications: this.importSendNotifications(),
        skipConflicts: this.importSkipConflicts(),
      })
      .subscribe({
        next: (res) => {
          this.saving.set(false);
          this.importResult.set(res);
          this.load(this.page());
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(
            err.error?.message ?? this.translate.instant('ASSIGN_ADMIN.IMPORT_ERROR'),
          );
        },
      });
  }

  toggleMultiHospital(id: string): void {
    this.multiHospitalIds.update((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  private patchRow(updated: AssignmentResponse): void {
    this.rows.update((list) => list.map((r) => (r.id === updated.id ? updated : r)));
    if (this.selected()?.id === updated.id) this.selected.set(updated);
  }
}
