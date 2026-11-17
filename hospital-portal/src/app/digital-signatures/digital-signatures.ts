import { textMatches } from '../shared/person-search';
import {
  Component,
  inject,
  OnInit,
  signal,
  computed,
  ChangeDetectionStrategy,
  DestroyRef,
} from '@angular/core';

import { Subscription } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { actionRoles } from '../core/action-permissions';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { EnumLabelService } from '../core/enum-label.service';
import {
  DigitalSignatureService,
  SIGNABLE_TYPES,
  SignableReport,
  SIGNATURE_TYPES,
  SignatureResponse,
  SignatureAuditEntry,
  SignatureType,
} from '../services/digital-signature.service';

import { currentLocale } from '../shared/i18n/app-locale';
import { serverInstant, toServerDateTime } from '../shared/date-utils';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';
import { refusalReason } from '../shared/refusal-reason';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
@Component({
  selector: 'app-digital-signatures',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ModalDialogDirective,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
  ],
  templateUrl: './digital-signatures.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './digital-signatures.scss',
})
export class DigitalSignaturesComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private lookupRequest?: Subscription;
  private listRequest?: Subscription;
  private readonly service = inject(DigitalSignatureService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  private readonly enumLabels = inject(EnumLabelService);

  /**
   * Every gate of this page comes from ACTION_PERMISSIONS, which
   * ActionPermissionsMirrorTest holds to DigitalSignatureController's
   * @PreAuthorize (review #1196/#1198 L4: the report lookup was shown to
   * HOSPITAL_ADMIN/SUPER_ADMIN, whom GET /signatures/report/… refuses).
   */
  private readonly canListAll = this.roleContext.hasAnyActiveRole(actionRoles('signatureListAll'));

  /** POST /signatures/sign roles (admins cannot sign). */
  readonly canSign = this.roleContext.hasAnyActiveRole(actionRoles('signatureSign'));

  /** GET /signatures/report/{type}/{id}: clinicians only. */
  readonly canLookup = this.roleContext.hasAnyActiveRole(actionRoles('signatureReportLookup'));

  /** POST /signatures/{id}/revoke roles (QA D2120: never a super-admin alone). */
  readonly canRevoke = this.roleContext.hasAnyActiveRole(actionRoles('signatureRevoke'));

  /** GET /signatures/{id}/audit-trail roles. */
  readonly canViewAudit = this.roleContext.hasAnyActiveRole(actionRoles('signatureAuditTrail'));

  loading = signal(false);
  loadError = signal(false);
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
  /** QA D627: the picker's rows; the key is `${type}:${id}`. */
  signableReports = signal<SignableReport[]>([]);
  signableLoading = signal(false);
  signPick = signal('');
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
  verifyError = signal(false);
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
  lookupError = signal(false);
  lookupValidation = signal(false);
  lookupSigned = signal<boolean | null>(null);
  lookupSignatures = signal<SignatureResponse[]>([]);

  readonly reportTypes = SIGNATURE_TYPES;
  /** QA D627: the sign modal offers only what the server can sign. */
  readonly signableTypes = SIGNABLE_TYPES;
  readonly statuses = ['PENDING', 'SIGNED', 'REVOKED', 'EXPIRED', 'INVALID'];

  filteredSignatures = computed(() => {
    let list = this.signatures();
    const q = this.search().trim().toLowerCase();
    const st = this.statusFilter();
    const tp = this.typeFilter();

    if (q) {
      list = list.filter(
        (s) =>
          textMatches(s.signedByName, q) ||
          s.hospitalName?.toLowerCase().includes(q) ||
          s.reportType?.toLowerCase().includes(q) ||
          s.reportId?.toLowerCase().includes(q),
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

  hasFilters = computed(
    () => !!this.search().trim() || this.statusFilter() !== 'ALL' || this.typeFilter() !== 'ALL',
  );

  resetFilters(): void {
    this.search.set('');
    this.statusFilter.set('ALL');
    this.typeFilter.set('ALL');
  }

  knownReports = computed(() =>
    this.signatures().filter(
      (s, i, list) =>
        list.findIndex(
          (other) => other.reportType === s.reportType && other.reportId === s.reportId,
        ) === i,
    ),
  );

  /** Review L3: the known-report choice, reset with the lookup (it was a constant ''). */
  knownReportChoice = signal('');

  chooseKnownReport(signatureId: string): void {
    const signature = this.signatures().find((s) => s.id === signatureId);
    this.onLookupChange();
    if (!signature) return;
    this.knownReportChoice.set(signature.id);
    this.lookupReportType.set(signature.reportType);
    this.lookupReportId.set(signature.reportId);
  }

  signedCount = computed(() => this.signatures().filter((s) => s.status === 'SIGNED').length);
  revokedCount = computed(() => this.signatures().filter((s) => s.status === 'REVOKED').length);

  ngOnInit(): void {
    this.loadSignatures();
  }

  loadSignatures(): void {
    this.listRequest?.unsubscribe();
    this.loadError.set(false);
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
    this.listRequest = call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (data) => {
        this.signatures.set(data ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.signatures.set([]);
        this.loadError.set(true);
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
    this.signPick.set('');
    this.signValue.set('');
    this.signNotes.set('');
    this.signExpiresAt.set('');
    this.signableReports.set([]);
    this.showSignModal.set(true);
    this.signableLoading.set(true);
    this.service.listSignable().subscribe({
      next: (rows) => {
        this.signableReports.set(rows ?? []);
        this.signableLoading.set(false);
      },
      error: () => {
        this.signableLoading.set(false);
        this.toast.error(this.translate.instant('SIGNATURES.SIGNABLE_LOAD_ERROR'));
      },
    });
  }

  signableKey(r: SignableReport): string {
    return `${r.reportType}:${r.reportId}`;
  }

  /** The visible label: type, patient, date. Never the id. */
  signableLabel(r: SignableReport): string {
    const parts = [this.formatReportType(r.reportType)];
    if (r.patientName) {
      parts.push(r.patientName);
    }
    if (r.createdAt) {
      parts.push(serverInstant(r.createdAt).toLocaleDateString(currentLocale()));
    }
    return parts.join(' · ');
  }

  pickSignable(key: string): void {
    this.signPick.set(key);
    const row = this.signableReports().find((r) => this.signableKey(r) === key);
    this.signReportType.set(row ? row.reportType : '');
    this.signReportId.set(row ? row.reportId : '');
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
        // QA D777/D784: the picker gives local wall time; the server keeps UTC.
        expiresAt: this.signExpiresAt() ? toServerDateTime(this.signExpiresAt()) : undefined,
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
    if (this.verifying()) return;
    this.verifyError.set(false);
    this.verifySignatureId.set(sig.id);
    this.verifyReportType.set(sig.reportType);
    this.verifyReportId.set(sig.reportId);
    this.verifyValue.set('');
    this.verifyResult.set(null);
    this.showVerifyModal.set(true);
  }

  closeVerify(): void {
    if (this.verifying()) return;
    this.showVerifyModal.set(false);
  }

  doVerify(): void {
    if (this.verifying()) return;
    const reportType = this.verifyReportType();
    if (!reportType || !this.verifyValue().trim()) {
      this.toast.error(this.translate.instant('SIGNATURES.VERIFY_VALUE_REQUIRED'));
      return;
    }
    this.verifyError.set(false);
    this.verifyResult.set(null);
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
          this.verifyError.set(true);
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

  onLookupChange(): void {
    this.knownReportChoice.set('');
    this.lookupRequest?.unsubscribe();
    this.lookupLoading.set(false);
    this.lookupDone.set(false);
    this.lookupError.set(false);
    this.lookupValidation.set(false);
    this.lookupSignatures.set([]);
    this.lookupSigned.set(null);
  }

  runLookup(): void {
    if (this.lookupLoading()) return;
    const reportType = this.lookupReportType();
    const reportId = this.lookupReportId().trim();
    if (!reportType || !reportId) {
      this.toast.error(this.translate.instant('SIGNATURES.LOOKUP_REQUIRED'));
      return;
    }
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(reportId)) {
      this.lookupValidation.set(true);
      return;
    }
    this.lookupValidation.set(false);
    this.lookupError.set(false);
    this.lookupLoading.set(true);
    this.lookupDone.set(false);
    this.lookupRequest = this.service
      .listByReport(reportType, reportId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.lookupSignatures.set(list ?? []);
          this.lookupSigned.set((list ?? []).some((s) => s.status === 'SIGNED'));
          this.lookupLoading.set(false);
          this.lookupDone.set(true);
        },
        error: (err: unknown) => {
          this.lookupError.set(true);
          this.lookupSignatures.set([]);
          this.lookupSigned.set(null);
          this.lookupLoading.set(false);
          this.lookupDone.set(true);
          this.toast.error(refusalReason(err) ?? this.translate.instant('SIGNATURES.LOOKUP_ERROR'));
        },
      });
  }

  clearLookup(): void {
    this.onLookupChange();
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
    return serverInstant(dt).toLocaleDateString(currentLocale(), {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  formatReportType(type: string): string {
    return type ? this.enumLabels.transform(type, 'signatureType') : '—';
  }
}
