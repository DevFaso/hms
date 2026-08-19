import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SecurityRuleType } from './org-security.service';

/*
 * Super-admin governance — /super-admin/users, /super-admin/credentials,
 * /super-admin/security[ /rules]. Every endpoint is SUPER_ADMIN-only via
 * method @PreAuthorize (no SecurityConfig matcher). Bare DTOs/Lists.
 * Both imports take CSV as a JSON string field (csvContent) — not multipart.
 * Baseline export returns a base64 JSON envelope, not a file stream.
 * Rule sets are create-only (no list/get/update/delete on the backend).
 */

/* ── Users ── */

export interface UserBulkImportRequest {
  csvContent: string;
  defaultHospitalId?: string;
  forcePasswordChange?: boolean;
  sendInviteEmails?: boolean;
  delimiter?: string;
}

export interface UserImportResult {
  rowNumber: number;
  identifier?: string;
  success: boolean;
  message?: string;
  userId?: string;
}

export interface UserBulkImportResponse {
  processed: number;
  imported: number;
  failed: number;
  results: UserImportResult[];
}

export interface ForcePasswordResetRequest {
  userIds?: string[];
  emails?: string[];
  usernames?: string[];
  sendEmail?: boolean;
  reason?: string;
}

export interface ResetResult {
  userId?: string;
  email?: string;
  success: boolean;
  message?: string;
}

export interface ForcePasswordResetResponse {
  requested: number;
  succeeded: number;
  results: ResetResult[];
}

export type PasswordRotationStatus = 'HEALTHY' | 'WARNING' | 'FORCE_REQUIRED';

export interface PasswordRotationRow {
  userId: string;
  username?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  forcePasswordChange: boolean;
  passwordChangedAt?: string;
  rotationDueOn?: string;
  warningStartsOn?: string;
  passwordAgeDays: number;
  daysUntilDue: number;
  status: PasswordRotationStatus;
}

/* ── Credential health ── */

export type MfaMethodType =
  | 'TOTP'
  | 'SMS'
  | 'EMAIL'
  | 'PUSH'
  | 'APP_PUSH'
  | 'SECURITY_KEY'
  | 'BACKUP_CODE';
export type RecoveryContactType = 'EMAIL' | 'PHONE' | 'SECURITY_KEY' | 'PERSONAL_CONTACT' | 'OTHER';

export interface MfaEnrollment {
  method: MfaMethodType;
  channel?: string;
  enabled: boolean;
  primaryFactor: boolean;
  enrolledAt?: string;
  lastVerifiedAt?: string;
}

export interface RecoveryContact {
  id?: string;
  contactType: RecoveryContactType;
  contactValue: string;
  verified: boolean;
  verifiedAt?: string;
  primaryContact: boolean;
  notes?: string;
}

export interface CredentialHealth {
  userId: string;
  username?: string;
  email?: string;
  active: boolean;
  forcePasswordChange: boolean;
  forceUsernameChange: boolean;
  lastLoginAt?: string;
  mfaEnrolledCount: number;
  verifiedMfaCount: number;
  hasPrimaryMfa: boolean;
  recoveryContactCount: number;
  verifiedRecoveryContacts: number;
  hasPrimaryRecoveryContact: boolean;
  mfaEnrollments: MfaEnrollment[];
  recoveryContacts: RecoveryContact[];
}

/* ── Baselines ── */

export interface BaselineRequest {
  title: string;
  summary?: string;
  /** Free-form string on the backend, not an enum. */
  enforcementLevel: string;
  policyCount?: number;
  controlObjectivesJson?: string;
}

export interface BaselineResponse {
  id: string;
  baselineVersion: string;
  title: string;
  summary?: string;
  enforcementLevel: string;
  policyCount?: number;
  controlObjectivesJson?: string;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ApprovalSummary {
  id: string;
  policyName?: string;
  changeType?: string;
  requestedBy?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  submittedAt?: string;
  requiredBy?: string;
  summary?: string;
  baselineVersion?: string;
  severity?: string;
}

export interface BaselineExport {
  baselineVersion: string;
  fileName: string;
  contentType: string;
  base64Content: string;
  generatedAt: string;
}

/* ── Rule sets / templates / simulation ── */

export interface RuleDefinition {
  name: string;
  code: string;
  description?: string;
  ruleType: SecurityRuleType;
  ruleValue?: string;
  priority?: number;
  controllers?: string[];
}

export interface RuleSetRequest {
  name: string;
  description?: string;
  /** Free-form string on the backend, not an enum. */
  enforcementScope: string;
  rules?: RuleDefinition[];
}

export interface RuleSetResponse {
  id: string;
  code: string;
  name: string;
  description?: string;
  enforcementScope: string;
  ruleCount?: number;
  publishedBy?: string;
  publishedAt?: string;
  createdAt?: string;
  rules: RuleDefinition[];
}

export interface RuleTemplate {
  code: string;
  title: string;
  category: string;
  summary: string;
  controllers: string[];
  defaultRules: RuleDefinition[];
}

export interface TemplateImportResponse {
  templateCode: string;
  templateTitle: string;
  importedRuleCount: number;
  ruleSet: RuleSetResponse;
  importedRules: RuleDefinition[];
  importedAt: string;
}

export interface SimulationRequest {
  scenario: string;
  rules: RuleDefinition[];
}

export interface SimulationResult {
  scenario: string;
  evaluatedRuleCount: number;
  impactScore: number;
  impactedControllers: string[];
  recommendedActions: string[];
  evaluatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class SuperAdminGovernanceService {
  private readonly http = inject(HttpClient);

  /* ── Users ── */

  importUsers(req: UserBulkImportRequest): Observable<UserBulkImportResponse> {
    return this.http.post<UserBulkImportResponse>('/super-admin/users/import', req);
  }

  forcePasswordReset(req: ForcePasswordResetRequest): Observable<ForcePasswordResetResponse> {
    return this.http.post<ForcePasswordResetResponse>(
      '/super-admin/users/force-password-reset',
      req,
    );
  }

  passwordRotation(): Observable<PasswordRotationRow[]> {
    return this.http.get<PasswordRotationRow[]>('/super-admin/users/password-rotation');
  }

  /* ── Credential health ── */

  credentialHealth(): Observable<CredentialHealth[]> {
    return this.http.get<CredentialHealth[]>('/super-admin/credentials/health');
  }

  /* ── Security baselines ── */

  createBaseline(req: BaselineRequest): Observable<BaselineResponse> {
    return this.http.post<BaselineResponse>('/super-admin/security/policies/baselines', req);
  }

  pendingApprovals(): Observable<ApprovalSummary[]> {
    return this.http.get<ApprovalSummary[]>('/super-admin/security/policies/approvals/pending');
  }

  /** 404 when no baseline exists yet; client decodes base64Content itself. */
  exportLatestBaseline(): Observable<BaselineExport> {
    return this.http.get<BaselineExport>('/super-admin/security/policies/export/latest');
  }

  /* ── Rule sets ── */

  createRuleSet(req: RuleSetRequest): Observable<RuleSetResponse> {
    return this.http.post<RuleSetResponse>('/super-admin/security/rules/rule-sets', req);
  }

  listTemplates(): Observable<RuleTemplate[]> {
    return this.http.get<RuleTemplate[]>('/super-admin/security/rules/templates');
  }

  /** Always creates a NEW rule set — the backend ignores targetRuleSetId. */
  importTemplate(templateCode: string): Observable<TemplateImportResponse> {
    return this.http.post<TemplateImportResponse>('/super-admin/security/rules/templates/import', {
      templateCode,
    });
  }

  /** Backend 404s (not 400) on an empty rules array — callers must guard. */
  simulate(req: SimulationRequest): Observable<SimulationResult> {
    return this.http.post<SimulationResult>('/super-admin/security/rules/simulations', req);
  }
}
