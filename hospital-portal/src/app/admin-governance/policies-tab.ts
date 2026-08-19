import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  OrgSecurityService,
  SECURITY_POLICY_TYPES,
  SECURITY_RULE_TYPES,
  SecurityPolicyResponse,
  SecurityRuleResponse,
} from '../services/org-security.service';
import { OrganizationService, OrganizationResponse } from '../services/organization.service';
import { ToastService } from '../core/toast.service';

/**
 * Org security policies + rules via the flat CRUD controllers
 * (/security-policies, /security-rules) — the only pair supporting true field
 * updates and deletes. PUT is full-replace server-side, so edits always send
 * the complete object.
 */
@Component({
  selector: 'app-gov-policies-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './policies-tab.html',
  styleUrl: './admin-governance.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PoliciesTabComponent implements OnInit {
  private readonly service = inject(OrgSecurityService);
  private readonly orgService = inject(OrganizationService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly policyTypes = SECURITY_POLICY_TYPES;
  readonly ruleTypes = SECURITY_RULE_TYPES;

  policies = signal<SecurityPolicyResponse[]>([]);
  loading = signal(false);
  loadError = signal(false);
  expanded = signal<Set<string>>(new Set());
  organizations = signal<OrganizationResponse[]>([]);
  saving = signal(false);

  /* Policy modal */
  policyModal = signal(false);
  editingPolicy = signal<SecurityPolicyResponse | null>(null);
  pName = signal('');
  pCode = signal('');
  pDescription = signal('');
  pType = signal('');
  pOrganizationId = signal('');
  pPriority = signal(0);
  pActive = signal(true);
  pEnforceStrict = signal(false);

  /* Rule modal */
  ruleModal = signal(false);
  editingRule = signal<SecurityRuleResponse | null>(null);
  rulePolicy = signal<SecurityPolicyResponse | null>(null);
  rName = signal('');
  rCode = signal('');
  rDescription = signal('');
  rType = signal('');
  rValue = signal('');
  rPriority = signal(0);
  rActive = signal(true);

  ngOnInit(): void {
    this.load();
    this.orgService.list(0, 100).subscribe({
      next: (page) => this.organizations.set(page.content ?? []),
      error: () => this.toast.error(this.translate.instant('ADMIN_GOV.ORGS_LOAD_ERROR')),
    });
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.service.listPolicies().subscribe({
      next: (list) => {
        this.policies.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  toggleExpand(id: string): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  /* ── Policy CRUD ── */

  openCreatePolicy(): void {
    this.editingPolicy.set(null);
    this.pName.set('');
    this.pCode.set('');
    this.pDescription.set('');
    this.pType.set('');
    this.pOrganizationId.set('');
    this.pPriority.set(0);
    this.pActive.set(true);
    this.pEnforceStrict.set(false);
    this.policyModal.set(true);
  }

  openEditPolicy(policy: SecurityPolicyResponse): void {
    this.editingPolicy.set(policy);
    this.pName.set(policy.name);
    this.pCode.set(policy.code);
    this.pDescription.set(policy.description ?? '');
    this.pType.set(policy.policyType);
    this.pOrganizationId.set(policy.organizationId);
    this.pPriority.set(policy.priority ?? 0);
    this.pActive.set(policy.active);
    this.pEnforceStrict.set(policy.enforceStrict);
    this.policyModal.set(true);
  }

  closePolicyModal(): void {
    this.policyModal.set(false);
    this.editingPolicy.set(null);
  }

  submitPolicy(): void {
    if (!this.pName().trim() || !this.pCode().trim() || !this.pType() || !this.pOrganizationId()) {
      this.toast.error(this.translate.instant('ADMIN_GOV.POLICY_REQUIRED'));
      return;
    }
    const req = {
      name: this.pName().trim(),
      code: this.pCode().trim(),
      description: this.pDescription().trim() || undefined,
      policyType: this.pType() as SecurityPolicyResponse['policyType'],
      organizationId: this.pOrganizationId(),
      priority: this.pPriority(),
      active: this.pActive(),
      enforceStrict: this.pEnforceStrict(),
    };
    this.saving.set(true);
    const editing = this.editingPolicy();
    const call = editing
      ? this.service.updatePolicy(editing.id, req)
      : this.service.createPolicy(req);
    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.toast.success(
          this.translate.instant(editing ? 'ADMIN_GOV.POLICY_UPDATED' : 'ADMIN_GOV.POLICY_CREATED'),
        );
        if (editing) {
          // Flat PUT responses may omit child rules — keep the ones we had.
          this.policies.update((list) =>
            list.map((p) => (p.id === saved.id ? { ...saved, rules: saved.rules ?? p.rules } : p)),
          );
        } else {
          this.policies.update((list) => [{ ...saved, rules: saved.rules ?? [] }, ...list]);
        }
        this.closePolicyModal();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('ADMIN_GOV.SAVE_ERROR'));
      },
    });
  }

  deletePolicy(policy: SecurityPolicyResponse): void {
    if (!confirm(this.translate.instant('ADMIN_GOV.POLICY_DELETE_CONFIRM'))) return;
    this.service.deletePolicy(policy.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ADMIN_GOV.POLICY_DELETED'));
        this.policies.update((list) => list.filter((p) => p.id !== policy.id));
      },
      error: () => this.toast.error(this.translate.instant('ADMIN_GOV.POLICY_DELETE_ERROR')),
    });
  }

  /* ── Rule CRUD ── */

  openCreateRule(policy: SecurityPolicyResponse): void {
    this.editingRule.set(null);
    this.rulePolicy.set(policy);
    this.rName.set('');
    this.rCode.set('');
    this.rDescription.set('');
    this.rType.set('');
    this.rValue.set('');
    this.rPriority.set(0);
    this.rActive.set(true);
    this.ruleModal.set(true);
  }

  openEditRule(policy: SecurityPolicyResponse, rule: SecurityRuleResponse): void {
    this.editingRule.set(rule);
    this.rulePolicy.set(policy);
    this.rName.set(rule.name);
    this.rCode.set(rule.code);
    this.rDescription.set(rule.description ?? '');
    this.rType.set(rule.ruleType);
    this.rValue.set(rule.ruleValue ?? '');
    this.rPriority.set(rule.priority ?? 0);
    this.rActive.set(rule.active);
    this.ruleModal.set(true);
  }

  closeRuleModal(): void {
    this.ruleModal.set(false);
    this.editingRule.set(null);
    this.rulePolicy.set(null);
  }

  submitRule(): void {
    const policy = this.rulePolicy();
    if (!policy) return;
    if (!this.rName().trim() || !this.rCode().trim() || !this.rType()) {
      this.toast.error(this.translate.instant('ADMIN_GOV.RULE_REQUIRED'));
      return;
    }
    const req = {
      name: this.rName().trim(),
      code: this.rCode().trim(),
      description: this.rDescription().trim() || undefined,
      ruleType: this.rType() as SecurityRuleResponse['ruleType'],
      ruleValue: this.rValue().trim() || undefined,
      securityPolicyId: policy.id,
      priority: this.rPriority(),
      active: this.rActive(),
    };
    this.saving.set(true);
    const editing = this.editingRule();
    const call = editing ? this.service.updateRule(editing.id, req) : this.service.createRule(req);
    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.toast.success(
          this.translate.instant(editing ? 'ADMIN_GOV.RULE_UPDATED' : 'ADMIN_GOV.RULE_CREATED'),
        );
        this.policies.update((list) =>
          list.map((p) => {
            if (p.id !== policy.id) return p;
            const rules = editing
              ? p.rules.map((r) => (r.id === saved.id ? saved : r))
              : [...p.rules, saved];
            return { ...p, rules };
          }),
        );
        this.closeRuleModal();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.toast.error(err.error?.message ?? this.translate.instant('ADMIN_GOV.SAVE_ERROR'));
      },
    });
  }

  deleteRule(policy: SecurityPolicyResponse, rule: SecurityRuleResponse): void {
    if (!confirm(this.translate.instant('ADMIN_GOV.RULE_DELETE_CONFIRM'))) return;
    this.service.deleteRule(rule.id).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('ADMIN_GOV.RULE_DELETED'));
        this.policies.update((list) =>
          list.map((p) =>
            p.id === policy.id ? { ...p, rules: p.rules.filter((r) => r.id !== rule.id) } : p,
          ),
        );
      },
      error: () => this.toast.error(this.translate.instant('ADMIN_GOV.SAVE_ERROR')),
    });
  }
}
