import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  SecurityPolicyService,
  SecurityPolicyApproval,
  SecurityPolicyBaselineRequest,
} from '../services/security-policy.service';
import { ToastService } from '../core/toast.service';

type ActiveTab = 'approvals' | 'create' | 'export';

@Component({
  selector: 'app-security-policies',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './security-policies.html',
  styleUrl: './security-policies.scss',
})
export class SecurityPoliciesComponent implements OnInit {
  private readonly policyService = inject(SecurityPolicyService);
  private readonly toast = inject(ToastService);

  activeTab = signal<ActiveTab>('approvals');
  loading = signal(true);

  pendingApprovals = signal<SecurityPolicyApproval[]>([]);
  saving = signal(false);
  exporting = signal(false);

  // Create baseline form
  baselineForm: SecurityPolicyBaselineRequest = {
    title: '',
    summary: '',
    enforcementLevel: 'RECOMMENDED',
    policyCount: 0,
    controlObjectivesJson: '',
    createdBy: '',
  };

  readonly enforcementLevels = ['RECOMMENDED', 'REQUIRED', 'STRICT', 'AUDIT_ONLY'];

  ngOnInit(): void {
    this.loadApprovals();
  }

  switchTab(tab: ActiveTab): void {
    this.activeTab.set(tab);
    if (tab === 'approvals') this.loadApprovals();
  }

  loadApprovals(): void {
    this.loading.set(true);
    this.policyService.listPendingApprovals().subscribe({
      next: (approvals) => {
        this.pendingApprovals.set(approvals);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load pending approvals');
        this.loading.set(false);
      },
    });
  }

  createBaseline(): void {
    if (!this.baselineForm.title?.trim()) {
      this.toast.error('Title is required');
      return;
    }
    if (!this.baselineForm.enforcementLevel?.trim()) {
      this.toast.error('Enforcement level is required');
      return;
    }
    this.saving.set(true);
    this.policyService.createBaseline(this.baselineForm).subscribe({
      next: () => {
        this.toast.success('Security policy baseline created');
        this.saving.set(false);
        this.resetForm();
        this.switchTab('approvals');
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(err?.error?.message ?? 'Failed to create baseline');
      },
    });
  }

  exportBaseline(): void {
    this.exporting.set(true);
    this.policyService.exportLatestBaseline().subscribe({
      next: (data) => {
        if (data.base64Content) {
          const byteCharacters = atob(data.base64Content);
          const byteNumbers = new Array(byteCharacters.length);
          for (let i = 0; i < byteCharacters.length; i++) {
            byteNumbers[i] = byteCharacters.charCodeAt(i);
          }
          const byteArray = new Uint8Array(byteNumbers);
          const blob = new Blob([byteArray], { type: data.contentType || 'application/json' });
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = data.fileName || `security-baseline-${data.baselineVersion}.json`;
          a.click();
          window.URL.revokeObjectURL(url);
        }
        this.exporting.set(false);
        this.toast.success('Baseline exported successfully');
      },
      error: () => {
        this.exporting.set(false);
        this.toast.error('Failed to export baseline');
      },
    });
  }

  getSeverityClass(severity: string): string {
    switch (severity?.toUpperCase()) {
      case 'CRITICAL':
        return 'severity-critical';
      case 'HIGH':
        return 'severity-high';
      case 'MEDIUM':
        return 'severity-medium';
      default:
        return 'severity-low';
    }
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'APPROVED':
        return 'status-success';
      case 'REJECTED':
        return 'status-failure';
      case 'PENDING':
        return 'status-warning';
      default:
        return 'status-info';
    }
  }

  private resetForm(): void {
    this.baselineForm = {
      title: '',
      summary: '',
      enforcementLevel: 'RECOMMENDED',
      policyCount: 0,
      controlObjectivesJson: '',
      createdBy: '',
    };
  }
}
