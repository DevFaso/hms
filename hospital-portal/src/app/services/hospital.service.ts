import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface HospitalRequest {
  name: string;
  address?: string;
  city: string;
  state?: string;
  zipCode?: string;
  country: string;
  province?: string;
  region?: string;
  sector?: string;
  poBox?: string;
  phoneNumber: string;
  email?: string;
  website?: string;
  organizationId?: string;
  active?: boolean;
}

export interface HospitalResponse {
  id: string;
  name: string;
  code: string;
  address: string;
  city: string;
  state: string;
  zipCode: string;
  country: string;
  province: string;
  region: string;
  sector: string;
  poBox: string;
  phoneNumber: string;
  email: string;
  website: string;
  organizationId: string;
  organizationName: string;
  organizationCode: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class HospitalService {
  private readonly http = inject(HttpClient);

  list(filters?: {
    organizationId?: string;
    city?: string;
    state?: string;
    unassignedOnly?: boolean;
  }): Observable<HospitalResponse[]> {
    let params = new HttpParams();
    if (filters?.organizationId) params = params.set('organizationId', filters.organizationId);
    if (filters?.city) params = params.set('city', filters.city);
    if (filters?.state) params = params.set('state', filters.state);
    if (filters?.unassignedOnly) params = params.set('unassignedOnly', 'true');
    return this.http.get<HospitalResponse[]>('/hospitals', { params });
  }

  getById(id: string): Observable<HospitalResponse> {
    return this.http.get<HospitalResponse>(`/hospitals/${id}`);
  }

  create(req: HospitalRequest): Observable<HospitalResponse> {
    return this.http.post<HospitalResponse>('/hospitals', req);
  }

  update(id: string, req: HospitalRequest): Observable<HospitalResponse> {
    return this.http.put<HospitalResponse>(`/hospitals/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/hospitals/${id}`);
  }
}
