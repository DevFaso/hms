import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ToastService } from '../core/toast.service';

interface SignatureItem {
  id: string;
  reportType: string;
  reportId: string;
  signedByStaffId: string;
  signedByName: string;
  hospitalId: string;
  hospitalName: string;
  signatureValue: string;
  signatureDateTime: string;
  status: string;
  signatureNotes: string;
  revocationReason: string;
  revokedAt: string;
  revokedByDisplay: string;
  verificationCount: number;
  lastVerifiedAt: string;
  isValid: boolean;
  createdAt: string;
}

interface AuditEntry {
  action: string;
  performedByDisplay: string;
  performedAt: string;
  details: string;
  ipAddress: string;
}

@Component({
  selector: 'app-digital-signatures',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './digital-signatures.html',
  styleUrl: './digital-signatures.scss',
})
export class DigitalSignaturesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);

  loading = signal(true);
  signatures = signal<SignatureItem[]>([]);
  search = signal('');
  statusFilter = signal('ALL');
  typeFilter = signal('ALL');

  // Detail drawer
  selectedSignature = signal<SignatureItem | null>(null);
  auditTrail = signal<AuditEntry[]>([]);
  auditLoading = signal(false);

  // Verify modal
  showVerifyModal = signal(false);
  verifySignatureId = signal('');
  verifyValue = signal('');
  verifying = signal(false);
  verifyResult = signal<{ valid: boolean; message: string } | null>(null);

  // Revoke modal
  showRevokeModal = signal(false);
  revokeSignatureId = signal('');
  revokeReason = signal('');
  revoking = signal(false);

  reportTypes = [
    'DISCHARGE_SUMMARY',
    'LAB_RESULT',
    'IMAGING_REPORT',
    'OPERATIVE_NOTE',
    'CONSULTATION_NOTE',
    'PROGRESS_NOTE',
    'PROCEDURE_REPORT',
    'PATHOLOGY_REPORT',
    'ED_NOTE',
    'MEDICATION_ORDER',
    'CARE_PLAN',
    'GENERIC_REPORT',
  ];

  statuses = ['PENDING', 'SIGNED', 'REVOKED', 'EXPIRED', 'INVALID'];

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
    // Load all signatures via provider endpoint (super admin context)
    this.http.get<SignatureItem[]>('/api/signatures/all').subscribe({
      next: (data) => {
        this.signatures.set(data ?? []);
        this.loading.set(false);
      },
      error: () => {
        // Fallback: load empty if endpoint doesn't exist yet
        this.signatures.set([]);
        this.loading.set(false);
      },
    });
  }

  openDetail(sig: SignatureItem): void {
    this.selectedSignature.set(sig);
    this.auditTrail.set([]);
    this.auditLoading.set(true);
    this.http.get<AuditEntry[]>(`/api/signatures/${sig.id}/audit-trail`).subscribe({
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

  openVerify(sig: SignatureItem): void {
    this.verifySignatureId.set(sig.id);
    this.verifyValue.set('');
    this.verifyResult.set(null);
    this.showVerifyModal.set(true);
  }

  closeVerify(): void {
    this.showVerifyModal.set(false);
  }

  doVerify(): void {
    this.verifying.set(true);
    this.http
      .post<{ valid: boolean; message: string; verifiedAt: string }>('/api/signatures/verify', {
        signatureId: this.verifySignatureId(),
        signatureValue: this.verifyValue(),
      })
      .subscribe({
        next: (res) => {
          this.verifyResult.set({ valid: res.valid, message: res.message || '' });
          this.verifying.set(false);
        },
        error: () => {
          this.verifyResult.set({ valid: false, message: 'Verification failed' });
          this.verifying.set(false);
        },
      });
  }

  openRevoke(sig: SignatureItem): void {
    this.revokeSignatureId.set(sig.id);
    this.revokeReason.set('');
    this.showRevokeModal.set(true);
  }

  closeRevoke(): void {
    this.showRevokeModal.set(false);
  }

  doRevoke(): void {
    if (!this.revokeReason().trim()) {
      this.toast.error('Revocation reason is required');
      return;
    }
    this.revoking.set(true);
    this.http
      .post<SignatureItem>(`/api/signatures/${this.revokeSignatureId()}/revoke`, {
        reason: this.revokeReason().trim(),
      })
      .subscribe({
        next: () => {
          this.toast.success('Signature revoked');
          this.closeRevoke();
          this.revoking.set(false);
          this.loadSignatures();
        },
        error: () => {
          this.toast.error('Revocation failed');
          this.revoking.set(false);
        },
      });
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

  formatDate(dt: string): string {
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
