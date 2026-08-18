import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ImagingModality =
  | 'XRAY'
  | 'CT'
  | 'MRI'
  | 'ULTRASOUND'
  | 'MAMMOGRAPHY'
  | 'FLUOROSCOPY'
  | 'PET'
  | 'NUCLEAR_MEDICINE'
  | 'INTERVENTIONAL_RADIOLOGY'
  | 'DEXA'
  | 'OTHER';
export type ImagingOrderStatus =
  | 'DRAFT'
  | 'ORDERED'
  | 'PENDING_AUTHORIZATION'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'RESULTS_AVAILABLE'
  | 'CANCELLED';
export type ImagingPriority = 'ROUTINE' | 'URGENT' | 'STAT';
export type ImagingLaterality = 'LEFT' | 'RIGHT' | 'BILATERAL' | 'MIDLINE' | 'NOT_APPLICABLE';

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
  laterality: ImagingLaterality | '';
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
  laterality?: ImagingLaterality;
  clinicalQuestion?: string;
}

export type ImagingReportStatus =
  | 'DRAFT'
  | 'PRELIMINARY'
  | 'FINAL'
  | 'ADDENDUM'
  | 'CORRECTED'
  | 'AMENDED'
  | 'CANCELLED'
  | 'ERROR';

export interface ImagingReportMeasurement {
  id: string;
  sequenceNumber: number;
  label: string;
  region: string;
  numericValue: number | null;
  textValue: string | null;
  unit: string;
  referenceMin: number | null;
  referenceMax: number | null;
  abnormal: boolean | null;
  notes: string;
}

export interface ImagingReportStatusEntry {
  id: string;
  status: ImagingReportStatus;
  statusReason: string;
  changedAt: string;
  changedByName: string;
  notes: string;
}

export interface ImagingReportResponse {
  id: string;
  imagingOrderId: string;
  hospitalId: string;
  reportNumber: string;
  reportStatus: ImagingReportStatus;
  reportVersion: number;
  latestVersion: boolean;
  reportTitle: string;
  modality: ImagingModality;
  bodyRegion: string;
  technique: string;
  findings: string;
  impression: string;
  recommendations: string;
  comparisonStudies: string;
  contrastAdministered: boolean | null;
  performedByName: string;
  interpretingProviderName: string;
  signedByName: string;
  signedAt: string | null;
  criticalResultFlaggedAt: string | null;
  criticalResultAcknowledgedAt: string | null;
  criticalResultAckByName: string | null;
  measurements?: ImagingReportMeasurement[] | null;
  statusHistory?: ImagingReportStatusEntry[] | null;
  createdAt: string;
  updatedAt: string;
}

export interface ImagingReportStatusUpdateRequest {
  status?: ImagingReportStatus;
  statusReason?: string;
  changedByStaffId?: string;
  notes?: string;
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

  /* ── Results / reports ── */

  getReport(reportId: string): Observable<ImagingReportResponse> {
    return this.http.get<ImagingReportResponse>(`${this.baseUrl}/results/${reportId}`);
  }

  getLatestReportByOrder(orderId: string): Observable<ImagingReportResponse> {
    return this.http.get<ImagingReportResponse>(`${this.baseUrl}/results/order/${orderId}`);
  }

  getReportsByOrder(orderId: string): Observable<ImagingReportResponse[]> {
    return this.http.get<ImagingReportResponse[]>(`${this.baseUrl}/results/order/${orderId}/all`);
  }

  /** Hospital-wide reports. Backend precedence: status wins over modality; neither → FINAL. */
  getReportsByHospital(
    hospitalId: string,
    params?: { status?: ImagingReportStatus; modality?: ImagingModality },
  ): Observable<ImagingReportResponse[]> {
    let httpParams = new HttpParams();
    if (params?.status) httpParams = httpParams.set('status', params.status);
    if (params?.modality) httpParams = httpParams.set('modality', params.modality);
    return this.http.get<ImagingReportResponse[]>(
      `${this.baseUrl}/results/hospital/${hospitalId}`,
      { params: httpParams },
    );
  }

  updateReportStatus(
    reportId: string,
    update: ImagingReportStatusUpdateRequest,
  ): Observable<ImagingReportResponse> {
    return this.http.put<ImagingReportResponse>(
      `${this.baseUrl}/results/${reportId}/status`,
      update,
    );
  }

  acknowledgeCriticalReport(
    reportId: string,
    acknowledgingStaffId: string,
  ): Observable<ImagingReportResponse> {
    const params = new HttpParams().set('acknowledgingStaffId', acknowledgingStaffId);
    return this.http.put<ImagingReportResponse>(
      `${this.baseUrl}/results/${reportId}/acknowledge-critical`,
      null,
      { params },
    );
  }
}
