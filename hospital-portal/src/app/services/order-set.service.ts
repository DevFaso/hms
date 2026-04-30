import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CdsCard } from '../shared/cds-card/cds-card.model';

export type OrderSetItem = Record<string, unknown>;

export interface OrderSetSummary {
  id: string;
  name: string;
  description?: string | null;
  admissionType: string;
  departmentId?: string | null;
  departmentName?: string | null;
  hospitalId: string;
  hospitalName?: string | null;
  orderItems: OrderSetItem[];
  clinicalGuidelines?: string | null;
  active: boolean;
  version: number;
  createdById?: string | null;
  createdByName?: string | null;
  lastModifiedById?: string | null;
  lastModifiedByName?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  deactivatedAt?: string | null;
  deactivationReason?: string | null;
  orderCount: number;
}

export interface OrderSetRequest {
  name: string;
  description?: string | null;
  admissionType: string;
  departmentId?: string | null;
  hospitalId: string;
  orderItems: OrderSetItem[];
  clinicalGuidelines?: string | null;
  active?: boolean;
  createdByStaffId: string;
}

export interface ApplyOrderSetRequest {
  encounterId: string;
  orderingStaffId: string;
  forceOverride?: boolean;
}

export interface AppliedOrderSetSummary {
  orderSetId: string;
  orderSetName: string;
  orderSetVersion: number;
  admissionId: string;
  encounterId: string;
  prescriptionIds: string[];
  labOrderIds: string[];
  imagingOrderIds: string[];
  skippedItemCount: number;
  cdsAdvisories: CdsCard[];
}

interface PageEnvelope<T> {
  content: T[];
  totalElements: number;
  number: number;
  size: number;
}

/**
 * Wraps the {@code /api/order-sets} REST endpoints. The auth interceptor
 * prepends {@code /api}, so the resolved paths are
 * {@code /api/order-sets/...}.
 */
@Injectable({ providedIn: 'root' })
export class OrderSetService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/order-sets';

  list(hospitalId: string, search?: string, page = 0, size = 20): Observable<PageEnvelope<OrderSetSummary>> {
    let params = new HttpParams().set('hospitalId', hospitalId).set('page', page).set('size', size);
    if (search && search.trim()) params = params.set('search', search.trim());
    return this.http.get<PageEnvelope<OrderSetSummary>>(this.baseUrl, { params });
  }

  getById(id: string): Observable<OrderSetSummary> {
    return this.http.get<OrderSetSummary>(`${this.baseUrl}/${id}`);
  }

  versions(id: string): Observable<OrderSetSummary[]> {
    return this.http.get<OrderSetSummary[]>(`${this.baseUrl}/${id}/versions`);
  }

  create(request: OrderSetRequest): Observable<OrderSetSummary> {
    return this.http.post<OrderSetSummary>(this.baseUrl, request);
  }

  update(id: string, request: OrderSetRequest): Observable<OrderSetSummary> {
    return this.http.put<OrderSetSummary>(`${this.baseUrl}/${id}`, request);
  }

  deactivate(id: string, reason: string, actingStaffId: string): Observable<OrderSetSummary> {
    const params = new HttpParams().set('reason', reason).set('actingStaffId', actingStaffId);
    return this.http.delete<OrderSetSummary>(`${this.baseUrl}/${id}`, { params });
  }

  apply(orderSetId: string, admissionId: string, request: ApplyOrderSetRequest): Observable<AppliedOrderSetSummary> {
    return this.http.post<AppliedOrderSetSummary>(
      `${this.baseUrl}/${orderSetId}/apply/${admissionId}`,
      request,
    );
  }
}
