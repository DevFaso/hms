import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Organization security policies + rules — flat /security-policies and
 * /security-rules controllers (full CRUD, true field updates).
 * Backend allows SUPER_ADMIN + HOSPITAL_ADMIN, but reads are findAll() with
 * NO tenant scoping — a HOSPITAL_ADMIN would see every organization's
 * policies. The client therefore gates this UI to SUPER_ADMIN only.
 * PUT is full-replace (omitted fields null out — except priority, which is
 * kept when omitted): always send the complete object.
 * DELETE is hard, with no FK guard (child rules → 400 on restrictive FK).
 */

export type SecurityPolicyType =
  | 'ACCESS_CONTROL'
  | 'DATA_PROTECTION'
  | 'AUDIT_LOGGING'
  | 'PASSWORD_POLICY'
  | 'SESSION_MANAGEMENT'
  | 'ROLE_MANAGEMENT'
  | 'MULTI_FACTOR_AUTH'
  | 'API_RATE_LIMITING'
  | 'COMPLIANCE';
export const SECURITY_POLICY_TYPES: SecurityPolicyType[] = [
  'ACCESS_CONTROL',
  'DATA_PROTECTION',
  'AUDIT_LOGGING',
  'PASSWORD_POLICY',
  'SESSION_MANAGEMENT',
  'ROLE_MANAGEMENT',
  'MULTI_FACTOR_AUTH',
  'API_RATE_LIMITING',
  'COMPLIANCE',
];

export type SecurityRuleType =
  | 'ROLE_PERMISSION'
  | 'ENDPOINT_ACCESS'
  | 'DATA_FILTER'
  | 'PASSWORD_STRENGTH'
  | 'SESSION'
  | 'SESSION_TIMEOUT'
  | 'IP_WHITELIST'
  | 'API_RATE_LIMIT'
  | 'MFA'
  | 'TWO_FACTOR_AUTH'
  | 'AUDIT_REQUIREMENT'
  | 'COMPLIANCE_CHECK';
export const SECURITY_RULE_TYPES: SecurityRuleType[] = [
  'ROLE_PERMISSION',
  'ENDPOINT_ACCESS',
  'DATA_FILTER',
  'PASSWORD_STRENGTH',
  'SESSION',
  'SESSION_TIMEOUT',
  'IP_WHITELIST',
  'API_RATE_LIMIT',
  'MFA',
  'TWO_FACTOR_AUTH',
  'AUDIT_REQUIREMENT',
  'COMPLIANCE_CHECK',
];

export interface SecurityPolicyRequest {
  name: string;
  code: string;
  description?: string;
  policyType: SecurityPolicyType;
  organizationId: string;
  priority?: number;
  active?: boolean;
  enforceStrict?: boolean;
}

export interface SecurityRuleResponse {
  id: string;
  name: string;
  code: string;
  description?: string;
  ruleType: SecurityRuleType;
  ruleValue?: string;
  priority?: number;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
  securityPolicyId: string;
  securityPolicyName?: string;
}

export interface SecurityPolicyResponse {
  id: string;
  name: string;
  code: string;
  description?: string;
  policyType: SecurityPolicyType;
  priority?: number;
  active: boolean;
  enforceStrict: boolean;
  createdAt?: string;
  updatedAt?: string;
  organizationId: string;
  organizationName?: string;
  rules: SecurityRuleResponse[];
}

export interface SecurityRuleRequest {
  name: string;
  code: string;
  description?: string;
  ruleType: SecurityRuleType;
  ruleValue?: string;
  securityPolicyId: string;
  priority?: number;
  active?: boolean;
}

@Injectable({ providedIn: 'root' })
export class OrgSecurityService {
  private readonly http = inject(HttpClient);

  /* ── Policies ── */

  listPolicies(): Observable<SecurityPolicyResponse[]> {
    return this.http.get<SecurityPolicyResponse[]>('/security-policies');
  }

  createPolicy(req: SecurityPolicyRequest): Observable<SecurityPolicyResponse> {
    return this.http.post<SecurityPolicyResponse>('/security-policies', req);
  }

  updatePolicy(id: string, req: SecurityPolicyRequest): Observable<SecurityPolicyResponse> {
    return this.http.put<SecurityPolicyResponse>(`/security-policies/${id}`, req);
  }

  deletePolicy(id: string): Observable<void> {
    return this.http.delete<void>(`/security-policies/${id}`);
  }

  /* ── Rules ── */

  createRule(req: SecurityRuleRequest): Observable<SecurityRuleResponse> {
    return this.http.post<SecurityRuleResponse>('/security-rules', req);
  }

  updateRule(id: string, req: SecurityRuleRequest): Observable<SecurityRuleResponse> {
    return this.http.put<SecurityRuleResponse>(`/security-rules/${id}`, req);
  }

  deleteRule(id: string): Observable<void> {
    return this.http.delete<void>(`/security-rules/${id}`);
  }
}
