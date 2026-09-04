import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type RoiRequestStatus = 'PENDING' | 'FULFILLED' | 'DENIED' | 'CANCELLED';
export type RoiRequesterType = 'PATIENT' | 'THIRD_PARTY';

export interface RoiRequest {
  id: string;
  patientId: string;
  patientName?: string;
  hospitalName?: string;
  requesterType: RoiRequesterType;
  requesterName?: string;
  requesterContact?: string;
  purpose?: string;
  scopeDescription?: string;
  status: RoiRequestStatus;
  requestedOn: string;
  decidedAt?: string;
  decidedByName?: string;
  decisionNote?: string;
}

export interface RoiPage {
  content: RoiRequest[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface RoiCreateRequest {
  requesterType: RoiRequesterType;
  requesterName?: string;
  requesterContact?: string;
  purpose: string;
  scopeDescription: string;
  requestedOn?: string;
}

/** Release-of-information workflow (Tier 2 item 39b). */
@Injectable({ providedIn: 'root' })
export class RoiService {
  private readonly http = inject(HttpClient);

  create(patientId: string, req: RoiCreateRequest): Observable<RoiRequest> {
    return this.http.post<RoiRequest>(`/patients/${patientId}/roi-requests`, req);
  }

  patientRequests(patientId: string): Observable<RoiRequest[]> {
    return this.http.get<RoiRequest[]>(`/patients/${patientId}/roi-requests`);
  }

  worklist(status: RoiRequestStatus, page = 0, size = 200): Observable<RoiPage> {
    const params = new HttpParams()
      .set('status', status)
      .set('page', String(page))
      .set('size', String(size));
    return this.http.get<RoiPage>('/roi-requests', { params });
  }

  fulfil(id: string, note?: string): Observable<RoiRequest> {
    return this.http.put<RoiRequest>(`/roi-requests/${id}/fulfil`, { note: note || null });
  }

  deny(id: string, note: string): Observable<RoiRequest> {
    return this.http.put<RoiRequest>(`/roi-requests/${id}/deny`, { note });
  }

  cancel(id: string, note?: string): Observable<RoiRequest> {
    return this.http.put<RoiRequest>(`/roi-requests/${id}/cancel`, { note: note || null });
  }
}
