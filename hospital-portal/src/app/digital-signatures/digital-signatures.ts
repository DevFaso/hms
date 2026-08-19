import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import {
  DigitalSignatureService,
  SIGNATURE_TYPES,
  SignatureResponse,
  SignatureAuditEntry,
  SignatureType,
} from '../services/digital-signature.service';

@Component({
  selector: 'app-digital-signatures',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './digital-signatures.html',
  styleUrl: './digital-signatures.scss',
})
export class DigitalSignaturesComponent implements OnInit {
  private readonly service = inject(DigitalSignatureService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

  /** GET /signatures/all is HOSPITAL_ADMIN/SUPER_ADMIN-only on the backend. */
  private readonly canListAll = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);

  /** POST /signatures/sign roles (admins cannot sign). */
  readonly canSign = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_NURSE',
    'ROLE_MIDWIFE',
    'ROLE_LAB_SCIENTIST',
    'ROLE_PHARMACIST',
  ]);

  /** POST /signatures/{id}/revoke roles. */
  readonly canRevoke = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);

  /** Audit trail is DOCTOR/HOSPITAL_ADMIN/SUPER_ADMIN — same list as revoke. */
  readonly canViewAudit = this.canRevoke;

  loading = signal(true);
  signatures = signal<SignatureResponse[]>([]);
  search = signal('');
  statusFilter = signal('ALL');
  typeFilter = signal('ALL');

  // Detail drawer
  selectedSignature = signal<SignatureResponse | null>(null);
  auditTrail = signal<SignatureAuditEntry[]>([]);
  auditLoading = signal(false);

  // Sign modal
  showSignModal = signal(false);
  signReportType = signal<SignatureType | ''>('');
  signReportId = signal('');
  signValue = signal('');
  signNotes = signal('');
  signExpiresAt = signal('');
  signing = signal(false);

  // Verify modal — the backend requires reportType + reportId even when a
  // signatureId is supplied, so both are carried from the selected row.
  showVerifyModal = signal(false);
  verifySignatureId = signal('');
  verifyReportType = signal<SignatureType | ''>('');
  verifyReportId = signal('');
  verifyValue = signal('');
  verifying = signal(false);
  verifyResult = signal<{ valid: boolean; message: string } | null>(null);

  // Revoke modal
  showRevokeModal = signal(false);
  revokeSignatureId = signal('');
  revokeReason = signal('');
  revoking = signal(false);

  // Report lookup panel
  lookupReportType = signal<SignatureType | ''>('');
  lookupReportId = signal('');
  lookupLoading = signal(false);
  lookupDone = signal(false);
  lookupSigned = signal<boolean | null>(null);
  lookupSignatures = signal<SignatureResponse[]>([]);

  readonly reportTypes = SIGNATURE_TYPES;
  readonly statuses = ['PENDING', 'SIGNED', 'REVOKED', 'EXPIRED', 'INVALID'];

  filteredSignatures = computed(() => {
    let list = this.signatures();
    const q = this.search().toLowerCase();
    const st = this.statusFilter();
    const tp = this.typeFilter();

    if (q) {
      list = list.filter(
        (s) =>
          s.signedByName?.toLowerCase().includes(q) ||
          s.hospitalName?.toLowerCase().includes(q) ||
          s.reportType?.toLowerCase().includes(q),
      );
    }
    if (st !== 'ALL') {
      list = list.filter((s) => s.status === st);
    }
    if (tp !== 'ALL') {
      list = list.filter((s) => s.reportType === tp);
    }
    return list;
  });

  signedCount = computed(() => this.signatures().filter((s) => s.status === 'SIGNED').length);
  revokedCount = computed(() => this.signatures().filter((s) => s.status === 'REVOKED').length);

  ngOnInit(): void {
    this.loadSignatures();
  }

  loadSignatures(): void {
    this.loading.set(true);
    // Admins see the hospital-wide list; clinicians are limited to their own
    // signatures (calling /signatures/all as DOCTOR hard-403s the page).
    const staffId = this.auth.getUserProfile()?.staffId;
    const call = this.canListAll
      ? this.service.listAll()
      : staffId
        ? this.service.listByProvider(staffId)
        : null;
    if (!call) {
      this.signatures.set([]);
      this.loading.set(false);
      return;
    }
    call.subscribe({
      next: (data) => {
        this.signatures.set(data ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.signatures.set([]);
        this.loading.set(false);
      },
    });
  }

  openDetail(sig: SignatureResponse): void {
    this.selectedSignature.set(sig);
    this.auditTrail.set([]);
    if (!this.canViewAudit) return;
    this.auditLoading.set(true);
    this.service.auditTrail(sig.id).subscribe({
      next: (data) => {
        this.auditTrail.set(data ?? []);
        this.auditLoading.set(false);
      },
      error: () => this.auditLoading.set(false),
    });
  }

  closeDetail(): void {
    this.selectedSignature.set(null);
  }

  /* ── Sign ── */

  openSign(): void {
    this.signReportType.set('');
    this.signReportId.set('');
    this.signValue.set('');
    this.signNotes.set('');
    this.signExpiresAt.set('');
    this.showSignModal.set(true);
  }

  closeSign(): void {
    this.showSignModal.set(false);
  }

  doSign(): void {
    const staffId = this.auth.getUserProfile()?.staffId;
    const hospitalId = this.roleContext.activeHospitalId;
    const reportType = this.signReportType();
    if (!reportType || !this.signReportId().trim() || !this.signValue().trim()) {
      this.toast.error(this.translate.instant('SIGNATURES.SIGN_REQUIRED'));
      return;
    }
    if (!staffId || !hospitalId) {
      this.toast.error(this.translate.instant('SIGNATURES.SIGN_NO_CONTEXT'));
      return;
    }
    this.signing.set(true);
    this.service
      .sign({
        reportType,
        reportId: this.signReportId().trim(),
        signedByStaffId: staffId,
        hospitalId,
        signatureValue: this.signValue().trim(),
        signatureNotes: this.signNotes().trim() || undefined,
        expiresAt: this.signExpiresAt() || undefined,
      })
      .subscribe({
        next: (created) => {
          this.signing.set(false);
          this.showSignModal.set(false);
          this.toast.success(this.translate.instant('SIGNATURES.SIGNED_OK'));
          this.signatures.update((list) => [created, ...list]);
        },
        error: (err: HttpErrorResponse) => {
          this.signing.set(false);
          // 400 "already signed — revoke first" carries the useful message.
          this.toast.error(err.error?.message ?? this.translate.instant('SIGNATURES.SIGN_ERROR'));
        },
      });
  }

  /* ── Verify ── */

  openVerify(sig: SignatureResponse): void {
    this.verifySignatureId.set(sig.id);
    this.verifyReportType.set(sig.reportType);
    this.verifyReportId.set(sig.reportId);
    this.verifyValue.set('');
    this.verifyResult.set(null);
    this.showVerifyModal.set(true);
  }

  closeVerify(): void {
    this.showVerifyModal.set(false);
  }

  doVerify(): void {
    const reportType = this.verifyReportType();
    if (!reportType || !this.verifyValue().trim()) {
      this.toast.error(this.translate.instant('SIGNATURES.VERIFY_VALUE_REQUIRED'));
      return;
    }
    this.verifying.set(true);
    this.service
      .verify({
        reportType,
        reportId: this.verifyReportId(),
        signatureId: this.verifySignatureId() || undefined,
        signatureValue: this.verifyValue(),
      })
      .subscribe({
        next: (res) => {
          this.verifyResult.set({
            valid: res.isValid === true,
            message: res.message || res.invalidReason || '',
          });
          this.verifying.set(false);
          // Verification bumps the server-side counter — refresh so the
          // detail drawer and list reflect it.
          this.loadSignatures();
        },
        error: () => {
          this.verifyResult.set({
            valid: false,
            message: this.translate.instant('SIGNATURES.VERIFY_FAILED'),
          });
          this.verifying.set(false);
        },
      });
  }

  /* ── Revoke ── */

  openRevoke(sig: SignatureResponse): void {
    this.revokeSignatureId.set(sig.id);
    this.revokeReason.set('');
    this.showRevokeModal.set(true);
  }

  closeRevoke(): void {
    this.showRevokeModal.set(false);
  }

  doRevoke(): void {
    if (!this.revokeReason().trim()) {
      this.toast.error(this.translate.instant('SIGNATURES.REVOKE_REASON_REQUIRED'));
      return;
    }
    this.revoking.set(true);
    this.service.revoke(this.revokeSignatureId(), this.revokeReason().trim()).subscribe({
      next: (revoked) => {
        this.toast.success(this.translate.instant('SIGNATURES.REVOKED_OK'));
        this.closeRevoke();
        this.revoking.set(false);
        this.signatures.update((list) => list.map((s) => (s.id === revoked.id ? revoked : s)));
      },
      error: (err: HttpErrorResponse) => {
        this.toast.error(err.error?.message ?? this.translate.instant('SIGNATURES.REVOKE_FAILED'));
        this.revoking.set(false);
      },
    });
  }

  /* ── Report lookup ── */

  runLookup(): void {
    const reportType = this.lookupReportType();
    const reportId = this.lookupReportId().trim();
    if (!reportType || !reportId) {
      this.toast.error(this.translate.instant('SIGNATURES.LOOKUP_REQUIRED'));
      return;
    }
    this.lookupLoading.set(true);
    this.lookupDone.set(false);
    this.service.listByReport(reportType, reportId).subscribe({
      next: (list) => {
        this.lookupSignatures.set(list ?? []);
        this.lookupSigned.set(list.some((s) => s.status === 'SIGNED'));
        this.lookupLoading.set(false);
        this.lookupDone.set(true);
      },
      error: () => {
        this.lookupSignatures.set([]);
        this.lookupSigned.set(null);
        this.lookupLoading.set(false);
        this.lookupDone.set(true);
        this.toast.error(this.translate.instant('SIGNATURES.LOOKUP_ERROR'));
      },
    });
  }

  clearLookup(): void {
    this.lookupReportType.set('');
    this.lookupReportId.set('');
    this.lookupSignatures.set([]);
    this.lookupSigned.set(null);
    this.lookupDone.set(false);
  }

  statusClass(status: string): string {
    const map: Record<string, string> = {
      SIGNED: 'badge-success',
      REVOKED: 'badge-danger',
      EXPIRED: 'badge-warning',
      INVALID: 'badge-danger',
      PENDING: 'badge-info',
    };
    return map[status] || 'badge-secondary';
  }

  formatDate(dt: string | undefined): string {
    if (!dt) return '—';
    return new Date(dt).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  formatReportType(type: string): string {
    return type ? type.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()) : '—';
  }
}
