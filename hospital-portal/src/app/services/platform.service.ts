import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ═══════════════════════════════════════════
   Enums
   ═══════════════════════════════════════════ */

export type PlatformServiceType =
  | 'EHR'
  | 'BILLING'
  | 'INVENTORY'
  | 'LIMS'
  | 'ANALYTICS'
  | 'CLINICAL_ANALYTICS'
  | 'REMOTE_MONITORING'
  | 'PEDIATRIC_MESSAGING'
  | 'ORTHO_IMAGING'
  | 'RESP_TELEMED';

export type PlatformServiceStatus = 'ACTIVE' | 'PILOT' | 'INACTIVE' | 'PENDING' | 'DECOMMISSIONED';

export type PlatformReleaseStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export type AutomationStatus = 'ON_TRACK' | 'AT_RISK' | 'BLOCKED';

/* ═══════════════════════════════════════════
   Shared DTOs
   ═══════════════════════════════════════════ */

export interface PlatformOwnership {
  ownerTeam?: string;
  ownerContactEmail?: string;
  dataSteward?: string;
  serviceLevel?: string;
}

export interface PlatformServiceMetadata {
  ehrSystem?: string;
  billingSystem?: string;
  inventorySystem?: string;
  integrationNotes?: string;
}

/* ═══════════════════════════════════════════
   Catalog (Integration Descriptors)
   ═══════════════════════════════════════════ */

export interface CatalogItem {
  id: string;
  serviceType: PlatformServiceType;
  displayName: string;
  description: string;
  provider: string;
  baseUrl: string;
  documentationUrl: string;
  sandboxUrl: string;
  onboardingGuideUrl: string;
  featureFlag: string;
  enabled: boolean;
  autoProvision: boolean;
  managedByPlatform: boolean;
  capabilities: string[];
  defaultOwnership?: PlatformOwnership;
  defaultMetadata?: PlatformServiceMetadata;
}

/* ═══════════════════════════════════════════
   Organization Services
   ═══════════════════════════════════════════ */

export interface OrgServiceResponse {
  id: string;
  organizationId: string;
  serviceType: PlatformServiceType;
  status: PlatformServiceStatus;
  provider: string;
  baseUrl: string;
  documentationUrl: string;
  /** Write-only server-side since item 45: reads say only whether it is set. */
  apiKeyReferenceSet: boolean;
  managedByPlatform: boolean;
  ownership?: PlatformOwnership;
  metadata?: PlatformServiceMetadata;
  hospitalLinkCount: number;
  departmentLinkCount: number;
}

export interface OrgServiceRegisterRequest {
  serviceType: PlatformServiceType;
  provider?: string;
  baseUrl?: string;
  documentationUrl?: string;
  apiKeyReference?: string;
  managedByPlatform?: boolean;
  ownership?: PlatformOwnership;
  metadata?: PlatformServiceMetadata;
}

/**
 * Partial update. Text fields: omitted = unchanged, `''` = cleared, anything
 * else replaces (ownership and metadata field by field). The API key is
 * write-only, so a blank value keeps it and `clearApiKeyReference` removes it.
 */
export interface OrgServiceUpdateRequest {
  status?: PlatformServiceStatus;
  provider?: string;
  baseUrl?: string;
  documentationUrl?: string;
  apiKeyReference?: string;
  clearApiKeyReference?: boolean;
  managedByPlatform?: boolean;
  ownership?: PlatformOwnership;
  metadata?: PlatformServiceMetadata;
}

/* ═══════════════════════════════════════════
   Hospital & Department Links
   ═══════════════════════════════════════════ */

export interface ServiceLinkRequest {
  enabled?: boolean;
  credentialsReference?: string;
  overrideEndpoint?: string;
  ownership?: PlatformOwnership;
}

export interface HospitalServiceLink {
  id: string;
  hospitalId: string;
  hospitalName: string;
  organizationServiceId: string;
  serviceType: PlatformServiceType;
  enabled: boolean;
  /** Write-only server-side since item 45: reads say only whether it is set. */
  credentialsReferenceSet: boolean;
  overrideEndpoint: string;
  ownership?: PlatformOwnership;
}

export interface DepartmentServiceLink {
  id: string;
  departmentId: string;
  departmentName: string;
  hospitalId: string;
  organizationServiceId: string;
  serviceType: PlatformServiceType;
  enabled: boolean;
  /** Write-only server-side since item 45: reads say only whether it is set. */
  credentialsReferenceSet: boolean;
  overrideEndpoint: string;
  ownership?: PlatformOwnership;
}

/* ═══════════════════════════════════════════
   Summary  (super-admin)
   ═══════════════════════════════════════════ */

export interface ModuleCard {
  title: string;
  description: string;
  meta: string;
  activeIntegrations: number;
  pendingIntegrations: number;
  managedIntegrations: number;
}

export interface AutomationTask {
  id: string;
  title: string;
  description: string;
  status: AutomationStatus;
  statusLabel: string;
  metricLabel: string;
  metricValue: string;
  nextAction: string;
}

export interface ActionPanel {
  totalIntegrations: number;
  pendingIntegrations: number;
  disabledLinks: number;
  activeReleaseWindows: number;
  /** When a release window was last created or changed (ISO local time); null when none exists. */
  lastReleaseWindowChangeAt?: string | null;
}

export interface PlatformSummary {
  modules: ModuleCard[];
  automationTasks: AutomationTask[];
  actions: ActionPanel;
}

/* ═══════════════════════════════════════════
   Release Windows
   ═══════════════════════════════════════════ */

export interface ReleaseWindowRequest {
  name: string;
  description?: string;
  environment: string;
  startsAt: string; // ISO datetime
  endsAt: string;
  freezeChanges?: boolean;
  ownerTeam?: string;
  notes?: string;
}

export interface ReleaseWindowResponse {
  id: string;
  name: string;
  description: string;
  environment: string;
  startsAt: string;
  endsAt: string;
  status: PlatformReleaseStatus;
  freezeChanges: boolean;
  ownerTeam: string;
  notes: string;
  createdAt: string;
  updatedAt: string;
}

/* ═══════════════════════════════════════════
   Snapshot
   ═══════════════════════════════════════════ */

export interface PlatformSnapshot {
  generatedAt: string;
  summary: PlatformSummary;
}

/* ═══════════════════════════════════════════
   Service
   ═══════════════════════════════════════════ */

@Injectable({ providedIn: 'root' })
export class PlatformService {
  private readonly http = inject(HttpClient);

  /* ── Catalog ── */

  getCatalog(includeDisabled = true): Observable<CatalogItem[]> {
    const params = new HttpParams().set('includeDisabled', String(includeDisabled));
    return this.http.get<CatalogItem[]>('/platform/catalog', { params });
  }

  getCatalogItem(serviceType: PlatformServiceType): Observable<CatalogItem> {
    return this.http.get<CatalogItem>(`/platform/catalog/${serviceType}`);
  }

  /* ── Summary / Snapshot ── */

  getSummary(): Observable<PlatformSummary> {
    return this.http.get<PlatformSummary>('/super-admin/platform/registry/summary');
  }

  getSnapshot(): Observable<PlatformSnapshot> {
    return this.http.get<PlatformSnapshot>('/super-admin/platform/registry/snapshot');
  }

  /* ── Organization Services ── */

  listOrgServices(orgId: string, status?: PlatformServiceStatus): Observable<OrgServiceResponse[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<OrgServiceResponse[]>(`/platform/organizations/${orgId}/services`, {
      params,
    });
  }

  getOrgService(orgId: string, serviceId: string): Observable<OrgServiceResponse> {
    return this.http.get<OrgServiceResponse>(
      `/platform/organizations/${orgId}/services/${serviceId}`,
    );
  }

  registerOrgService(
    orgId: string,
    request: OrgServiceRegisterRequest,
  ): Observable<OrgServiceResponse> {
    return this.http.post<OrgServiceResponse>(`/platform/organizations/${orgId}/services`, request);
  }

  updateOrgService(
    orgId: string,
    serviceId: string,
    request: OrgServiceUpdateRequest,
  ): Observable<OrgServiceResponse> {
    return this.http.put<OrgServiceResponse>(
      `/platform/organizations/${orgId}/services/${serviceId}`,
      request,
    );
  }

  /* ── Hospital Service Links ── */

  listHospitalLinks(hospitalId: string): Observable<HospitalServiceLink[]> {
    return this.http.get<HospitalServiceLink[]>(`/platform/hospitals/${hospitalId}/services`);
  }

  linkHospital(
    hospitalId: string,
    serviceId: string,
    request?: ServiceLinkRequest,
  ): Observable<HospitalServiceLink> {
    return this.http.post<HospitalServiceLink>(
      `/platform/hospitals/${hospitalId}/services/${serviceId}`,
      request ?? {},
    );
  }

  /** Enables or disables an existing link (POST would answer 409 for a link that exists). */
  setHospitalLinkEnabled(
    hospitalId: string,
    serviceId: string,
    enabled: boolean,
  ): Observable<HospitalServiceLink> {
    return this.http.put<HospitalServiceLink>(
      `/platform/hospitals/${hospitalId}/services/${serviceId}`,
      { enabled },
    );
  }

  /** Every hospital link of one organization service, across all its hospitals. */
  listServiceHospitalLinks(orgId: string, serviceId: string): Observable<HospitalServiceLink[]> {
    return this.http.get<HospitalServiceLink[]>(
      `/platform/organizations/${orgId}/services/${serviceId}/hospital-links`,
    );
  }

  unlinkHospital(hospitalId: string, serviceId: string): Observable<void> {
    return this.http.delete<void>(`/platform/hospitals/${hospitalId}/services/${serviceId}`);
  }

  /* ── Department Service Links ── */

  listDepartmentLinks(departmentId: string): Observable<DepartmentServiceLink[]> {
    return this.http.get<DepartmentServiceLink[]>(`/platform/departments/${departmentId}/services`);
  }

  linkDepartment(
    departmentId: string,
    serviceId: string,
    request?: ServiceLinkRequest,
  ): Observable<DepartmentServiceLink> {
    return this.http.post<DepartmentServiceLink>(
      `/platform/departments/${departmentId}/services/${serviceId}`,
      request ?? {},
    );
  }

  unlinkDepartment(departmentId: string, serviceId: string): Observable<void> {
    return this.http.delete<void>(`/platform/departments/${departmentId}/services/${serviceId}`);
  }

  /* ── Release Windows ── */

  /** Newest start first; the server caps the page. */
  listReleaseWindows(limit?: number): Observable<ReleaseWindowResponse[]> {
    let params = new HttpParams();
    if (limit) params = params.set('limit', String(limit));
    return this.http.get<ReleaseWindowResponse[]>('/super-admin/platform/release-windows', {
      params,
    });
  }

  scheduleReleaseWindow(request: ReleaseWindowRequest): Observable<ReleaseWindowResponse> {
    return this.http.post<ReleaseWindowResponse>('/super-admin/platform/release-windows', request);
  }
}
