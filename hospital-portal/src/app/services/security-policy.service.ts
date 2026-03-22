import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface SecurityPolicyBaseline {
  id: string;
  baselineVersion: string;
  title: string;
  summary: string;
  enforcementLevel: string;
  policyCount: number;
  controlObjectivesJson: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface SecurityPolicyBaselineRequest {
  title: string;
  summary?: string;
  enforcementLevel: string;
  policyCount?: number;
  controlObjectivesJson?: string;
  createdBy?: string;
}

export interface SecurityPolicyApproval {
  id: string;
  policyName: string;
  changeType: string;
  requestedBy: string;
  status: string;
  submittedAt: string;
  requiredBy: string;
  summary: string;
  baselineVersion: string;
  severity: string;
}

export interface SecurityPolicyExport {
  baselineVersion: string;
  fileName: string;
  contentType: string;
  base64Content: string;
  generatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class SecurityPolicyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/super-admin/security';

  createBaseline(request: SecurityPolicyBaselineRequest): Observable<SecurityPolicyBaseline> {
    return this.http.post<SecurityPolicyBaseline>(`${this.baseUrl}/policies/baselines`, request);
  }

  listPendingApprovals(): Observable<SecurityPolicyApproval[]> {
    return this.http.get<SecurityPolicyApproval[]>(`${this.baseUrl}/policies/approvals/pending`);
  }

  exportLatestBaseline(): Observable<SecurityPolicyExport> {
    return this.http.get<SecurityPolicyExport>(`${this.baseUrl}/policies/export/latest`);
  }
}
