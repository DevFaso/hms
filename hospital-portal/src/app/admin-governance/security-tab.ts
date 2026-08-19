import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ApprovalSummary,
  BaselineResponse,
  RuleSetResponse,
  RuleTemplate,
  SimulationResult,
  SuperAdminGovernanceService,
} from '../services/super-admin-governance.service';
import { ToastService } from '../core/toast.service';

/** Security baselines, pending approvals, rule-set templates + simulation. */
@Component({
  selector: 'app-gov-security-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './security-tab.html',
  styleUrl: './admin-governance.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecurityTabComponent implements OnInit {
  private readonly service = inject(SuperAdminGovernanceService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  saving = signal(false);

  /* Approvals */
  approvals = signal<ApprovalSummary[]>([]);
  approvalsLoading = signal(false);
  approvalsError = signal(false);

  /* Templates */
  templates = signal<RuleTemplate[]>([]);
  templatesLoading = signal(false);
  templatesError = signal(false);

  /* Created artefacts this session (backend has no list endpoints) */
  createdBaselines = signal<BaselineResponse[]>([]);
  createdRuleSets = signal<RuleSetResponse[]>([]);

  /* Baseline modal */
  baselineModal = signal(false);
  bTitle = signal('');
  bSummary = signal('');
  bEnforcementLevel = signal('GLOBAL');
  bPolicyCount = signal(0);

  /* Simulation modal */
  simModal = signal(false);
  simTemplate = signal<RuleTemplate | null>(null);
  simScenario = signal('');
  simResult = signal<SimulationResult | null>(null);

  exporting = signal(false);

  ngOnInit(): void {
    this.loadApprovals();
    this.loadTemplates();
  }

  loadApprovals(): void {
    this.approvalsLoading.set(true);
    this.approvalsError.set(false);
    this.service.pendingApprovals().subscribe({
      next: (rows) => {
        this.approvals.set(rows);
        this.approvalsLoading.set(false);
      },
      error: () => {
        this.approvalsLoading.set(false);
        this.approvalsError.set(true);
      },
    });
  }

  loadTemplates(): void {
    this.templatesLoading.set(true);
    this.templatesError.set(false);
    this.service.listTemplates().subscribe({
      next: (rows) => {
        this.templates.set(rows);
        this.templatesLoading.set(false);
      },
      error: () => {
        this.templatesLoading.set(false);
        this.templatesError.set(true);
      },
    });
  }

  /* ── Baselines ── */

  openBaseline(): void {
    this.bTitle.set('');
    this.bSummary.set('');
    this.bEnforcementLevel.set('GLOBAL');
    this.bPolicyCount.set(0);
    this.baselineModal.set(true);
  }

  closeBaseline(): void {
    this.baselineModal.set(false);
  }

  submitBaseline(): void {
    if (!this.bTitle().trim()) {
      this.toast.error(this.translate.instant('ADMIN_GOV.BASELINE_TITLE_REQUIRED'));
      return;
    }
    this.saving.set(true);
    this.service
      .createBaseline({
        title: this.bTitle().trim(),
        summary: this.bSummary().trim() || undefined,
        enforcementLevel: this.bEnforcementLevel().trim() || 'GLOBAL',
        policyCount: this.bPolicyCount(),
      })
      .subscribe({
        next: (baseline) => {
          this.saving.set(false);
          this.toast.success(this.translate.instant('ADMIN_GOV.BASELINE_CREATED'));
          this.createdBaselines.update((list) => [baseline, ...list]);
          this.closeBaseline();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(err.error?.message ?? this.translate.instant('ADMIN_GOV.SAVE_ERROR'));
        },
      });
  }

  exportLatest(): void {
    this.exporting.set(true);
    this.service.exportLatestBaseline().subscribe({
      next: (exp) => {
        this.exporting.set(false);
        // The backend hands back a base64 JSON envelope, not a file stream —
        // decode and trigger the download client-side.
        const bytes = Uint8Array.from(atob(exp.base64Content), (c) => c.charCodeAt(0));
        const blob = new Blob([bytes], { type: exp.contentType || 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = exp.fileName || 'security-policy-baseline.json';
        link.click();
        URL.revokeObjectURL(url);
      },
      error: (err: HttpErrorResponse) => {
        this.exporting.set(false);
        this.toast.error(
          err.status === 404
            ? this.translate.instant('ADMIN_GOV.NO_BASELINE_YET')
            : this.translate.instant('ADMIN_GOV.EXPORT_ERROR'),
        );
      },
    });
  }

  /* ── Templates / rule sets ── */

  importTemplate(template: RuleTemplate): void {
    this.saving.set(true);
    // The backend always creates a NEW rule set from the template.
    this.service.importTemplate(template.code).subscribe({
      next: (res) => {
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('ADMIN_GOV.TEMPLATE_IMPORTED', {
            count: res.importedRuleCount,
            code: res.ruleSet.code,
          }),
        );
        this.createdRuleSets.update((list) => [res.ruleSet, ...list]);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('ADMIN_GOV.SAVE_ERROR'));
      },
    });
  }

  /* ── Simulation ── */

  openSimulation(template: RuleTemplate): void {
    this.simTemplate.set(template);
    this.simScenario.set('');
    this.simResult.set(null);
    this.simModal.set(true);
  }

  closeSimulation(): void {
    this.simModal.set(false);
    this.simTemplate.set(null);
    this.simResult.set(null);
  }

  runSimulation(): void {
    const template = this.simTemplate();
    if (!template) return;
    if (!this.simScenario().trim()) {
      this.toast.error(this.translate.instant('ADMIN_GOV.SCENARIO_REQUIRED'));
      return;
    }
    // Backend 404s (not 400) on an empty rules array; template rules are
    // always non-empty so this guard is belt-and-braces.
    if (template.defaultRules.length === 0) {
      this.toast.error(this.translate.instant('ADMIN_GOV.SIM_RULES_REQUIRED'));
      return;
    }
    this.saving.set(true);
    this.service
      .simulate({ scenario: this.simScenario().trim(), rules: template.defaultRules })
      .subscribe({
        next: (result) => {
          this.saving.set(false);
          this.simResult.set(result);
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toast.error(err.error?.message ?? this.translate.instant('ADMIN_GOV.SAVE_ERROR'));
        },
      });
  }
}
