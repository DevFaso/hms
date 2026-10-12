import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  ProviderCreateRequest,
  ProviderDecisionRequest,
  ProviderFacilityType,
  ProviderPage,
  ProviderResponse,
  ProviderResubmitRequest,
  ProviderVerificationHistoryEntry,
  ProviderVerificationStatus,
  ProviderVerifyRequest,
} from './provider.model';

const BASE = '/super-admin/providers';

export interface ProviderListFilters {
  type?: ProviderFacilityType | '';
  status?: ProviderVerificationStatus | '';
}

/**
 * Super-admin onboarding of private pharmacies and laboratories (provider plan
 * US-1). Every endpoint is SUPER_ADMIN only; the backend also requires a
 * verified super-admin (a live assignment).
 */
@Injectable({ providedIn: 'root' })
export class SuperAdminProviderService {
  private readonly http = inject(HttpClient);

  list(filters: ProviderListFilters, page = 0, size = 20): Observable<ProviderPage> {
    let params = new HttpParams().set('page', String(page)).set('size', String(size));
    if (filters.type) params = params.set('type', filters.type);
    if (filters.status) params = params.set('status', filters.status);
    return this.http.get<ProviderPage>(BASE, { params });
  }

  get(id: string): Observable<ProviderResponse> {
    return this.http.get<ProviderResponse>(`${BASE}/${encodeURIComponent(id)}`);
  }

  history(id: string): Observable<ProviderVerificationHistoryEntry[]> {
    return this.http.get<ProviderVerificationHistoryEntry[]>(
      `${BASE}/${encodeURIComponent(id)}/verifications`,
    );
  }

  create(body: ProviderCreateRequest): Observable<ProviderResponse> {
    return this.http.post<ProviderResponse>(BASE, body);
  }

  verify(id: string, body: ProviderVerifyRequest): Observable<ProviderResponse> {
    return this.http.post<ProviderResponse>(`${BASE}/${encodeURIComponent(id)}/verify`, body);
  }

  reject(id: string, body: ProviderDecisionRequest): Observable<ProviderResponse> {
    return this.http.post<ProviderResponse>(`${BASE}/${encodeURIComponent(id)}/reject`, body);
  }

  resubmit(id: string, body: ProviderResubmitRequest): Observable<ProviderResponse> {
    return this.http.post<ProviderResponse>(`${BASE}/${encodeURIComponent(id)}/resubmit`, body);
  }

  revoke(id: string, body: ProviderDecisionRequest): Observable<ProviderResponse> {
    return this.http.post<ProviderResponse>(`${BASE}/${encodeURIComponent(id)}/revoke`, body);
  }
}
