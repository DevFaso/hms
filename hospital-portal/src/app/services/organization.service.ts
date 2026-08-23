import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface OrganizationHospital {
  id: string;
  name: string;
  code: string;
  city: string;
  active: boolean;
}

export interface OrganizationResponse {
  id: string;
  name: string;
  code: string;
  description: string;
  type: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  primaryContactEmail: string;
  primaryContactPhone: string;
  defaultTimezone: string;
  onboardingNotes: string;
  lifecycleState?: OrganizationLifecycleState;
  hospitals: OrganizationHospital[];
}

export interface OrganizationPage {
  content: OrganizationResponse[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface OrganizationCreateRequest {
  name: string;
  code: string;
  timezone: string;
  contactEmail: string;
  contactPhone?: string;
  notes?: string;
  type?: string;
}

/* ── Tenant lifecycle (MVP-2) ─────────────────────────────────────── */

export type OrganizationLifecycleState =
  'ACTIVE' | 'SUSPENDED' | 'ARCHIVED' | 'PENDING_PURGE' | 'PURGED';

export interface TenantLifecycleResponse {
  organizationId: string;
  organizationName: string;
  organizationCode: string;
  lifecycleState: OrganizationLifecycleState;
  suspendedAt?: string;
  suspendedBy?: string;
  suspensionReason?: string;
  archivedAt?: string;
  archivedBy?: string;
  archiveReason?: string;
  purgeScheduledFor?: string;
  purgeScheduledBy?: string;
  purgeReason?: string;
  purgedAt?: string;
  canSuspend: boolean;
  canRestore: boolean;
  canArchive: boolean;
  canSchedulePurge: boolean;
  canCancelPurge: boolean;
}

export interface TenantLifecycleActionRequest {
  reason?: string;
  /** ISO datetime — only consulted by schedule-purge */
  purgeScheduledFor?: string;
}

@Injectable({ providedIn: 'root' })
export class OrganizationService {
  private readonly http = inject(HttpClient);

  list(page = 0, size = 20, active?: boolean): Observable<OrganizationPage> {
    let params = new HttpParams().set('page', String(page)).set('size', String(size));
    if (active !== undefined) params = params.set('active', String(active));
    return this.http.get<OrganizationPage>('/organizations', { params });
  }

  getById(id: string, includePolicies = false): Observable<OrganizationResponse> {
    let params = new HttpParams();
    if (includePolicies) params = params.set('includePolicies', 'true');
    return this.http.get<OrganizationResponse>(`/organizations/${id}`, { params });
  }

  create(req: OrganizationCreateRequest): Observable<OrganizationResponse> {
    return this.http.post<OrganizationResponse>('/super-admin/organizations', req);
  }

  /** Fetch the list of valid organization type enum values from the backend */
  getTypes(): Observable<string[]> {
    return this.http.get<string[]>('/organizations/types');
  }

  update(id: string, req: Partial<OrganizationCreateRequest>): Observable<OrganizationResponse> {
    return this.http.put<OrganizationResponse>(`/organizations/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/organizations/${id}`);
  }

  /* ── Tenant lifecycle (MVP-2) ─────────────────────────────────────── */

  getLifecycle(id: string): Observable<TenantLifecycleResponse> {
    return this.http.get<TenantLifecycleResponse>(`/super-admin/organizations/${id}/lifecycle`);
  }

  suspend(
    id: string,
    body: TenantLifecycleActionRequest,
    mfaToken?: string,
  ): Observable<TenantLifecycleResponse> {
    return this.http.post<TenantLifecycleResponse>(
      `/super-admin/organizations/${id}/suspend`,
      body,
      mfaToken ? { headers: this.mfaHeaders(mfaToken) } : {},
    );
  }

  restoreLifecycle(
    id: string,
    body?: TenantLifecycleActionRequest,
  ): Observable<TenantLifecycleResponse> {
    return this.http.post<TenantLifecycleResponse>(
      `/super-admin/organizations/${id}/restore`,
      body ?? {},
    );
  }

  archive(
    id: string,
    body: TenantLifecycleActionRequest,
    mfaToken?: string,
  ): Observable<TenantLifecycleResponse> {
    return this.http.post<TenantLifecycleResponse>(
      `/super-admin/organizations/${id}/archive`,
      body,
      mfaToken ? { headers: this.mfaHeaders(mfaToken) } : {},
    );
  }

  schedulePurge(
    id: string,
    body: TenantLifecycleActionRequest,
    mfaToken?: string,
  ): Observable<TenantLifecycleResponse> {
    return this.http.post<TenantLifecycleResponse>(
      `/super-admin/organizations/${id}/schedule-purge`,
      body,
      mfaToken ? { headers: this.mfaHeaders(mfaToken) } : {},
    );
  }

  /**
   * Build the X-Mfa-Token header for destructive lifecycle actions. Backend
   * enforces this when {@code hms.tenant-lifecycle.require-mfa} is on; the
   * frontend should always send it when the user provides a TOTP code.
   */
  private mfaHeaders(mfaToken: string): HttpHeaders {
    return new HttpHeaders({ 'X-Mfa-Token': mfaToken });
  }

  cancelPurge(
    id: string,
    body?: TenantLifecycleActionRequest,
  ): Observable<TenantLifecycleResponse> {
    return this.http.post<TenantLifecycleResponse>(
      `/super-admin/organizations/${id}/cancel-purge`,
      body ?? {},
    );
  }
}
