import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ImagingModality =
  | 'XRAY'
  | 'CT'
  | 'MRI'
  | 'ULTRASOUND'
  | 'PET'
  | 'MAMMOGRAPHY'
  | 'FLUOROSCOPY'
  | 'NUCLEAR';
export type ImagingOrderStatus =
  | 'ORDERED'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'PRELIMINARY'
  | 'FINAL';
export type ImagingPriority = 'ROUTINE' | 'URGENT' | 'STAT' | 'ASAP';

export interface ImagingOrderResponse {
  id: string;
  patientId: string;
  patientDisplayName: string;
  patientMrn: string;
  hospitalId: string;
  hospitalName: string;
  modality: ImagingModality;
  studyType: string;
  bodyRegion: string;
  laterality: string;
  priority: ImagingPriority;
  status: ImagingOrderStatus;
  clinicalQuestion: string;
  orderingProviderId: string;
  orderingProviderName: string;
  orderedAt: string;
  scheduledDate: string;
  scheduledTime: string;
  completedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface ImagingOrderRequest {
  patientId: string;
  hospitalId: string;
  encounterId?: string;
  modality: ImagingModality;
  studyType: string;
  bodyRegion?: string;
  priority: ImagingPriority;
  laterality?: string;
  clinicalQuestion?: string;
}

export interface ImagingReportResponse {
  id: string;
  imagingOrderId: string;
  hospitalId: string;
  reportNumber: string;
  reportStatus: string;
  modality: ImagingModality;
  bodyRegion: string;
  findings: string;
  impression: string;
  recommendations: string;
  isCritical: boolean;
  performedByName: string;
  interpretingProviderName: string;
  createdAt: string;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class ImagingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/imaging';

  createOrder(req: ImagingOrderRequest): Observable<ImagingOrderResponse> {
    return this.http.post<ImagingOrderResponse>(`${this.baseUrl}/orders`, req);
  }

  updateOrder(
    orderId: string,
    req: Partial<ImagingOrderRequest>,
  ): Observable<ImagingOrderResponse> {
    return this.http.put<ImagingOrderResponse>(`${this.baseUrl}/orders/${orderId}`, req);
  }

  updateOrderStatus(
    orderId: string,
    statusUpdate: Record<string, unknown>,
  ): Observable<ImagingOrderResponse> {
    return this.http.put<ImagingOrderResponse>(
      `${this.baseUrl}/orders/${orderId}/status`,
      statusUpdate,
    );
  }

  getOrder(orderId: string): Observable<ImagingOrderResponse> {
    return this.http.get<ImagingOrderResponse>(`${this.baseUrl}/orders/${orderId}`);
  }

  getOrdersByPatient(patientId: string): Observable<ImagingOrderResponse[]> {
    return this.http.get<ImagingOrderResponse[]>(`${this.baseUrl}/orders/patient/${patientId}`);
  }

  getOrdersByHospital(
    hospitalId: string,
    params?: { status?: string },
  ): Observable<ImagingOrderResponse[]> {
    let httpParams = new HttpParams();
    if (params?.status) httpParams = httpParams.set('status', params.status);
    return this.http.get<ImagingOrderResponse[]>(`${this.baseUrl}/orders/hospital/${hospitalId}`, {
      params: httpParams,
    });
  }

  getAllOrders(params?: { status?: string }): Observable<ImagingOrderResponse[]> {
    let httpParams = new HttpParams();
    if (params?.status) httpParams = httpParams.set('status', params.status);
    return this.http.get<ImagingOrderResponse[]>(`${this.baseUrl}/orders`, { params: httpParams });
  }

  signOrder(orderId: string, signature: Record<string, unknown>): Observable<ImagingOrderResponse> {
    return this.http.post<ImagingOrderResponse>(
      `${this.baseUrl}/orders/${orderId}/signature`,
      signature,
    );
  }

  checkDuplicates(patientId: string): Observable<ImagingOrderResponse[]> {
    return this.http.get<ImagingOrderResponse[]>(
      `${this.baseUrl}/orders/patient/${patientId}/duplicates`,
    );
  }
}
