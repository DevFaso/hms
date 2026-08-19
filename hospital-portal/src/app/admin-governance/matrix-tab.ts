import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  MATRIX_ENVIRONMENTS,
  MatrixAuditAction,
  MatrixAuditEvent,
  MatrixEnvironment,
  MatrixRow,
  MatrixSnapshot,
  PermissionMatrixService,
} from '../services/permission-matrix.service';
import { ToastService } from '../core/toast.service';

interface EditableRow {
  domain: string;
  actions: string;
  owners: string;
}

/** Permission-matrix snapshots + audit trail (SUPER_ADMIN). */
@Component({
  selector: 'app-gov-matrix-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './matrix-tab.html',
  styleUrl: './admin-governance.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MatrixTabComponent implements OnInit {
  private readonly service = inject(PermissionMatrixService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly environments = MATRIX_ENVIRONMENTS;
  readonly auditActions: MatrixAuditAction[] = [
    'SNAPSHOT_PUBLISHED',
    'EXPORT_GENERATED',
    'COMPARISON_RUN',
  ];

  environment = signal<MatrixEnvironment>('CURRENT');
  latest = signal<MatrixSnapshot | null>(null);
  latestLoading = signal(false);
  /** true = the environment simply has no snapshot yet (backend 404s). */
  latestEmpty = signal(false);
  latestError = signal(false);

  snapshots = signal<MatrixSnapshot[]>([]);
  snapshotsLoading = signal(false);
  snapshotsError = signal(false);
  viewedSnapshot = signal<MatrixSnapshot | null>(null);

  audit = signal<MatrixAuditEvent[]>([]);
  auditLoading = signal(false);
  auditError = signal(false);
  auditFilter = signal('');

  /* Create-snapshot modal */
  showCreate = signal(false);
  saving = signal(false);
  createEnvironment = signal<MatrixEnvironment>('CURRENT');
  createLabel = signal('');
  createNotes = signal('');
  createRows = signal<EditableRow[]>([]);

  ngOnInit(): void {
    this.loadLatest();
    this.loadSnapshots();
    this.loadAudit();
  }

  loadLatest(): void {
    this.latestLoading.set(true);
    this.latestEmpty.set(false);
    this.latestError.set(false);
    this.latest.set(null);
    this.service.latestSnapshot(this.environment()).subscribe({
      next: (snap) => {
        this.latest.set(snap);
        this.latestLoading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.latestLoading.set(false);
        if (err.status === 404) this.latestEmpty.set(true);
        else this.latestError.set(true);
      },
    });
  }

  loadSnapshots(): void {
    this.snapshotsLoading.set(true);
    this.snapshotsError.set(false);
    this.service.listSnapshots().subscribe({
      next: (list) => {
        this.snapshots.set(list);
        this.snapshotsLoading.set(false);
      },
      error: () => {
        this.snapshotsLoading.set(false);
        this.snapshotsError.set(true);
      },
    });
  }

  loadAudit(): void {
    this.auditLoading.set(true);
    this.auditError.set(false);
    const action = this.auditFilter() as MatrixAuditAction | '';
    this.service.listAudit(action === '' ? undefined : action).subscribe({
      next: (list) => {
        this.audit.set(list);
        this.auditLoading.set(false);
      },
      error: () => {
        this.auditLoading.set(false);
        this.auditError.set(true);
      },
    });
  }

  onEnvironmentChange(env: string): void {
    this.environment.set(env as MatrixEnvironment);
    this.loadLatest();
  }

  onAuditFilterChange(action: string): void {
    this.auditFilter.set(action);
    this.loadAudit();
  }

  viewSnapshot(snap: MatrixSnapshot): void {
    this.viewedSnapshot.set(snap);
  }

  closeView(): void {
    this.viewedSnapshot.set(null);
  }

  openCreate(): void {
    this.createEnvironment.set(this.environment());
    this.createLabel.set('');
    this.createNotes.set('');
    const source = this.latest();
    // Prefill from the environment's latest snapshot so publishing a revision
    // means editing, not retyping, the whole matrix.
    this.createRows.set(
      source
        ? source.rows.map((r) => ({
            domain: r.domain,
            actions: r.actions.join(', '),
            owners: r.owners.join(', '),
          }))
        : [{ domain: '', actions: '', owners: '' }],
    );
    this.showCreate.set(true);
  }

  closeCreate(): void {
    this.showCreate.set(false);
  }

  addRow(): void {
    this.createRows.update((rows) => [...rows, { domain: '', actions: '', owners: '' }]);
  }

  removeRow(index: number): void {
    this.createRows.update((rows) => rows.filter((_, i) => i !== index));
  }

  updateRow(index: number, field: keyof EditableRow, value: string): void {
    this.createRows.update((rows) =>
      rows.map((r, i) => (i === index ? { ...r, [field]: value } : r)),
    );
  }

  submitCreate(): void {
    const rows: MatrixRow[] = this.createRows()
      .map((r) => ({
        domain: r.domain.trim(),
        actions: r.actions
          .split(',')
          .map((a) => a.trim())
          .filter(Boolean),
        owners: r.owners
          .split(',')
          .map((o) => o.trim())
          .filter(Boolean),
      }))
      .filter((r) => r.domain);
    if (rows.length === 0 || rows.some((r) => r.actions.length === 0 || r.owners.length === 0)) {
      this.toast.error(this.translate.instant('ADMIN_GOV.MATRIX_ROWS_REQUIRED'));
      return;
    }
    this.saving.set(true);
    this.service
      .createSnapshot({
        environment: this.createEnvironment(),
        label: this.createLabel().trim() || undefined,
        notes: this.createNotes().trim() || undefined,
        sourceSnapshotId: this.latest()?.id,
        rows,
      })
      .subscribe({
        next: (snap) => {
          this.saving.set(false);
          this.showCreate.set(false);
          this.toast.success(this.translate.instant('ADMIN_GOV.SNAPSHOT_CREATED'));
          this.snapshots.update((list) => [snap, ...list]);
          if (snap.environment === this.environment()) {
            this.latest.set(snap);
            this.latestEmpty.set(false);
          }
          // The backend logs the publish itself (SNAPSHOT_PUBLISHED).
          this.loadAudit();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(
            err.error?.message ?? this.translate.instant('ADMIN_GOV.SNAPSHOT_ERROR'),
          );
        },
      });
  }
}
