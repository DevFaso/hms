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
  'DRAFT' | 'PRELIMINARY' | 'FINAL' | 'ADDENDUM' | 'CORRECTED' | 'AMENDED' | 'CANCELLED' | 'ERROR';

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
  /** True once signed. Drives the read-only lock on the authoring form. */
  signed: boolean;
  /**
   * Null on a signed row means "signed outside this ceremony" — an externally
   * ingested report, or one written before V132 — not a tampered one.
   */
  signatureAlgorithm: string | null;
  signatureValue: string | null;
  criticalFinding: boolean;
  criticalAcknowledged: boolean;
  lockedForEditing?: boolean | null;
  measurements?: ImagingReportMeasurement[] | null;
  statusHistory?: ImagingReportStatusEntry[] | null;
  studyInstanceUid?: string | null;
  seriesInstanceUid?: string | null;
  accessionNumber?: string | null;
  pacsViewerUrl?: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Administrative void only. The backend restricts `status` to CANCELLED or
 * ERROR and requires a reason — content states come from authoring the report
 * and FINAL only from signing it. `changedByStaffId` is gone: the server
 * resolves the actor from the authenticated caller.
 */
export interface ImagingReportStatusUpdateRequest {
  status: Extract<ImagingReportStatus, 'CANCELLED' | 'ERROR'>;
  statusReason: string;
  clientSource?: string;
  notes?: string;
}

/**
 * What a radiologist may assert when authoring. Provenance is deliberately
 * absent — signer, sign time, acknowledger, version and latest-flag are all
 * server-owned. See ImagingReportUpsertRequestDTO on the backend.
 */
export interface ImagingReportAuthorRequest {
  imagingOrderId?: string;
  departmentId?: string;
  performedByStaffId?: string;
  interpretingProviderId?: string;
  reportNumber?: string;
  reportStatus?: Extract<
    ImagingReportStatus,
    'DRAFT' | 'PRELIMINARY' | 'ADDENDUM' | 'CORRECTED' | 'AMENDED'
  >;
  studyInstanceUid?: string;
  seriesInstanceUid?: string;
  accessionNumber?: string;
  pacsViewerUrl?: string;
  modality?: ImagingModality;
  bodyRegion?: string;
  reportTitle?: string;
  performedAt?: string;
  completedAt?: string;
  technique?: string;
  findings?: string;
  impression?: string;
  recommendations?: string;
  comparisonStudies?: string;
  contrastAdministered?: boolean;
  contrastDetails?: string;
  radiationDoseMgy?: number;
  /** Set-only: the backend never lowers a raised critical flag. */
  criticalFinding?: boolean;
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

  getReportsForOrder(orderId: string): Observable<ImagingReportResponse[]> {
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

  /**
   * The acknowledging clinician is the authenticated caller. It used to be an
   * `acknowledgingStaffId` query parameter — so a caller could record someone
   * else as having taken the call — and the endpoint threw on every request
   * regardless, because it forwarded a status-update payload with no status.
   */
  acknowledgeCriticalReport(reportId: string): Observable<ImagingReportResponse> {
    return this.http.put<ImagingReportResponse>(
      `${this.baseUrl}/results/${reportId}/acknowledge-critical`,
      null,
    );
  }

  /* ── Authoring (Tier 2 item 26) ── */

  createReport(req: ImagingReportAuthorRequest): Observable<ImagingReportResponse> {
    return this.http.post<ImagingReportResponse>(`${this.baseUrl}/results`, req);
  }

  updateReport(
    reportId: string,
    req: ImagingReportAuthorRequest,
  ): Observable<ImagingReportResponse> {
    return this.http.put<ImagingReportResponse>(`${this.baseUrl}/results/${reportId}`, req);
  }

  /** The only path to FINAL. Signer and time are stamped server-side. */
  signReport(reportId: string): Observable<ImagingReportResponse> {
    return this.http.post<ImagingReportResponse>(`${this.baseUrl}/results/${reportId}/sign`, null);
  }
}
