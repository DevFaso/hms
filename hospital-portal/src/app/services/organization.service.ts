import { HttpClient, HttpParams } from '@angular/common/http';
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
}
