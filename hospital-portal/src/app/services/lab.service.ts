import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

export interface LabOrderResponse {
  id: string;
  labOrderCode: string;
  patientFullName: string;
  patientEmail: string;
  hospitalName: string;
  labTestName: string;
  labTestCode: string;
  orderDatetime: string;
  status: string;
  clinicalIndication: string;
  medicalNecessityNote: string;
  notes: string;
  primaryDiagnosisCode: string;
  additionalDiagnosisCodes: string[];
  orderChannel: string;
  createdAt: string;
  updatedAt: string;
}

export interface LabResultResponse {
  id: string;
  labOrderId: string;
  resultValue: string;
  resultUnit: string;
  referenceRange: string;
  abnormalFlag: string;
  status: string;
  performedAt: string;
  verifiedAt: string;
  notes: string;
}

export interface LabOrderRequest {
  patientId: string;
  hospitalId: string;
  encounterId?: string;
  testName: string;
  testCode?: string;
  status: string;
  priority?: string;
  clinicalIndication: string;
  medicalNecessityNote: string;
  notes?: string;
}

interface ApiWrapper<T> {
  data: T;
  success: boolean;
}

interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class LabService {
  private readonly http = inject(HttpClient);

  listOrders(params?: {
    patientId?: string;
    page?: number;
    size?: number;
  }): Observable<LabOrderResponse[]> {
    let httpParams = new HttpParams();
    if (params?.patientId) httpParams = httpParams.set('patientId', params.patientId);
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http
      .get<ApiWrapper<PageResponse<LabOrderResponse>>>('/lab-orders', { params: httpParams })
      .pipe(map((res) => res?.data?.content ?? []));
  }

  getOrder(id: string): Observable<LabOrderResponse> {
    return this.http
      .get<ApiWrapper<LabOrderResponse>>(`/lab-orders/${id}`)
      .pipe(map((res) => res.data));
  }

  listResults(params?: { page?: number; size?: number }): Observable<LabResultResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page !== undefined) httpParams = httpParams.set('page', String(params.page));
    if (params?.size !== undefined) httpParams = httpParams.set('size', String(params.size));
    return this.http
      .get<ApiWrapper<PageResponse<LabResultResponse>>>('/lab-results', { params: httpParams })
      .pipe(map((res) => res?.data?.content ?? []));
  }

  getResult(id: string): Observable<LabResultResponse> {
    return this.http
      .get<ApiWrapper<LabResultResponse>>(`/lab-results/${id}`)
      .pipe(map((res) => res.data));
  }

  getPendingReviewResults(): Observable<LabResultResponse[]> {
    return this.http
      .get<ApiWrapper<LabResultResponse[]>>('/lab-results/pending-review')
      .pipe(map((res) => res?.data ?? []));
  }

  createOrder(req: LabOrderRequest): Observable<LabOrderResponse> {
    return this.http
      .post<ApiWrapper<LabOrderResponse>>('/lab-orders', req)
      .pipe(map((res) => res.data));
  }

  updateOrder(id: string, req: Partial<LabOrderRequest>): Observable<LabOrderResponse> {
    return this.http
      .put<ApiWrapper<LabOrderResponse>>(`/lab-orders/${id}`, req)
      .pipe(map((res) => res.data));
  }

  deleteOrder(id: string): Observable<void> {
    return this.http.delete<void>(`/lab-orders/${id}`);
  }
}
