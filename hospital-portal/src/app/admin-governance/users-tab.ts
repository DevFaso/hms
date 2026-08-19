import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ForcePasswordResetResponse,
  PasswordRotationRow,
  SuperAdminGovernanceService,
  UserBulkImportResponse,
} from '../services/super-admin-governance.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';

/** User governance: CSV import, forced password resets, rotation health. */
@Component({
  selector: 'app-gov-users-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './users-tab.html',
  styleUrl: './admin-governance.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UsersTabComponent implements OnInit {
  private readonly service = inject(SuperAdminGovernanceService);
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  hospitals = signal<HospitalResponse[]>([]);
  saving = signal(false);

  /* Rotation list */
  rotation = signal<PasswordRotationRow[]>([]);
  rotationLoading = signal(false);
  rotationError = signal(false);
  rotationFilter = signal('');

  /* Import modal */
  importModal = signal(false);
  importCsv = signal('');
  importFileName = signal('');
  importDelimiter = signal(',');
  importDefaultHospitalId = signal('');
  importForceChange = signal(true);
  importSendInvites = signal(true);
  importResult = signal<UserBulkImportResponse | null>(null);

  /* Force-reset modal */
  resetModal = signal(false);
  resetIdentifiers = signal('');
  resetSendEmail = signal(true);
  resetReason = signal('');
  resetResult = signal<ForcePasswordResetResponse | null>(null);

  ngOnInit(): void {
    this.loadRotation();
    this.hospitalService.list().subscribe({
      next: (h) => this.hospitals.set(h),
      error: () => this.toast.error(this.translate.instant('ASSIGN_ADMIN.HOSPITALS_LOAD_ERROR')),
    });
  }

  loadRotation(): void {
    this.rotationLoading.set(true);
    this.rotationError.set(false);
    this.service.passwordRotation().subscribe({
      next: (rows) => {
        this.rotation.set(rows);
        this.rotationLoading.set(false);
      },
      error: () => {
        this.rotationLoading.set(false);
        this.rotationError.set(true);
      },
    });
  }

  filteredRotation(): PasswordRotationRow[] {
    const filter = this.rotationFilter();
    return filter ? this.rotation().filter((r) => r.status === filter) : this.rotation();
  }

  /* ── Import ── */

  openImport(): void {
    this.importCsv.set('');
    this.importFileName.set('');
    this.importDelimiter.set(',');
    this.importDefaultHospitalId.set('');
    this.importForceChange.set(true);
    this.importSendInvites.set(true);
    this.importResult.set(null);
    this.importModal.set(true);
  }

  closeImport(): void {
    this.importModal.set(false);
  }

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
      .importUsers({
        csvContent: this.importCsv(),
        delimiter: this.importDelimiter() || undefined,
        defaultHospitalId: this.importDefaultHospitalId() || undefined,
        forcePasswordChange: this.importForceChange(),
        sendInviteEmails: this.importSendInvites(),
      })
      .subscribe({
        next: (res) => {
          this.saving.set(false);
          this.importResult.set(res);
          this.loadRotation();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(
            err.error?.message ?? this.translate.instant('ASSIGN_ADMIN.IMPORT_ERROR'),
          );
        },
      });
  }

  /* ── Force reset ── */

  openReset(): void {
    this.resetIdentifiers.set('');
    this.resetSendEmail.set(true);
    this.resetReason.set('');
    this.resetResult.set(null);
    this.resetModal.set(true);
  }

  closeReset(): void {
    this.resetModal.set(false);
  }

  submitReset(): void {
    const tokens = this.resetIdentifiers()
      .split(/[\n,;]+/)
      .map((t) => t.trim())
      .filter(Boolean);
    if (tokens.length === 0) {
      this.toast.error(this.translate.instant('ADMIN_GOV.RESET_IDENTIFIERS_REQUIRED'));
      return;
    }
    const emails = tokens.filter((t) => t.includes('@'));
    const usernames = tokens.filter((t) => !t.includes('@'));
    this.saving.set(true);
    this.service
      .forcePasswordReset({
        emails,
        usernames,
        sendEmail: this.resetSendEmail(),
        reason: this.resetReason().trim() || undefined,
      })
      .subscribe({
        next: (res) => {
          this.saving.set(false);
          this.resetResult.set(res);
          this.loadRotation();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(err.error?.message ?? this.translate.instant('ADMIN_GOV.SAVE_ERROR'));
        },
      });
  }
}
