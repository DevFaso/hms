import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type UltrasoundScanType =
  | 'NUCHAL_TRANSLUCENCY'
  | 'ANATOMY_SCAN'
  | 'GROWTH_SCAN'
  | 'BIOPHYSICAL_PROFILE'
  | 'DOPPLER_STUDY'
  | 'CERVICAL_LENGTH'
  | 'HIGH_RISK_FOLLOW_UP'
  | 'OTHER';
export type UltrasoundOrderStatus =
  'ORDERED' | 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'REPORT_AVAILABLE' | 'CANCELLED';
export type UltrasoundFindingCategory =
  'NORMAL' | 'VARIANT' | 'MONITORING_REQUIRED' | 'ABNORMAL' | 'CONCERNING_FOR_ANOMALY' | 'URGENT';

/** Shared report shape (UltrasoundReportBaseDTO). All fields optional on the wire. */
export interface UltrasoundReportFields {
  scanDate?: string;
  scanPerformedBy?: string;
  scanPerformedByCredentials?: string;
  gestationalAgeAtScan?: number;
  gestationalAgeDays?: number;
  nuchalTranslucencyMm?: number;
  crownRumpLengthMm?: number;
  nasalBonePresent?: boolean;
  estimatedDueDate?: string;
  dueDateConfirmed?: boolean;
  numberOfFetuses?: number;
  fetalPosition?: string;
  biparietalDiameterMm?: number;
  headCircumferenceMm?: number;
  abdominalCircumferenceMm?: number;
  femurLengthMm?: number;
  estimatedFetalWeightGrams?: number;
  placentalLocation?: string;
  placentalGrade?: string;
  amnioticFluidIndex?: number;
  amnioticFluidLevel?: string;
  cervicalLengthMm?: number;
  umbilicalArteryDoppler?: string;
  uterineArteryDoppler?: string;
  fetalHeartRate?: number;
  fetalCardiacActivity?: boolean;
  fetalMovementObserved?: boolean;
  fetalToneNormal?: boolean;
  anatomySurveyComplete?: boolean;
  anatomyFindings?: string;
  findingCategory?: UltrasoundFindingCategory;
  findingsSummary?: string;
  interpretation?: string;
  anomaliesDetected?: boolean;
  anomalyDescription?: string;
  geneticScreeningRecommended?: boolean;
  geneticScreeningType?: string;
  followUpRequired?: boolean;
  followUpRecommendations?: string;
  specialistReferralNeeded?: boolean;
  specialistReferralType?: string;
  nextUltrasoundRecommendedWeeks?: number;
}

export interface UltrasoundReportRequest extends UltrasoundReportFields {
  /** Required by the backend on submit. */
  scanDate: string;
  findingCategory: UltrasoundFindingCategory;
  reportFinalized?: boolean;
  providerReviewNotes?: string;
}

export interface UltrasoundReportResponse extends UltrasoundReportFields {
  id: string;
  ultrasoundOrderId?: string;
  reportFinalizedAt?: string | null;
  reportFinalizedBy?: string | null;
  reportReviewedByProvider?: boolean;
  providerReviewNotes?: string | null;
  patientNotified?: boolean;
  patientNotifiedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface UltrasoundOrderRequest {
  patientId: string;
  hospitalId: string;
  scanType: UltrasoundScanType;
  gestationalAgeAtOrder?: number;
  clinicalIndication?: string;
  scheduledDate?: string;
  /** Free-text on the backend (not LocalTime). */
  scheduledTime?: string;
  appointmentLocation?: string;
  /** Free-text on the backend; conventional values ROUTINE | URGENT | STAT. */
  priority?: string;
  isHighRiskPregnancy?: boolean;
  highRiskNotes?: string;
  specialInstructions?: string;
  scanCountForPregnancy?: number;
}

export interface UltrasoundOrderResponse {
  id: string;
  patientId: string;
  patientDisplayName?: string;
  patientMrn?: string;
  hospitalId: string;
  hospitalName?: string;
  scanType: UltrasoundScanType;
  status: UltrasoundOrderStatus;
  orderedDate?: string;
  orderedBy?: string;
  gestationalAgeAtOrder?: number | null;
  clinicalIndication?: string;
  scheduledDate?: string | null;
  scheduledTime?: string;
  appointmentLocation?: string;
  priority?: string;
  isHighRiskPregnancy?: boolean;
  highRiskNotes?: string;
  specialInstructions?: string;
  scanCountForPregnancy?: number | null;
  report?: UltrasoundReportResponse | null;
  cancelledAt?: string | null;
  cancelledBy?: string | null;
  cancellationReason?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Ultrasound orders/reports/templates — /ultrasound. Responses are bare DTOs
 * (no wrapper, no pagination — hospital lists are unbounded). Effective roles
 * (permission authorities are never granted): orders/report writes =
 * DOCTOR/MIDWIFE/SUPER_ADMIN; report review = DOCTOR/SUPER_ADMIN; hospital
 * lists = + HOSPITAL_ADMIN.
 */
@Injectable({ providedIn: 'root' })
export class UltrasoundService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/ultrasound';

  createOrder(req: UltrasoundOrderRequest): Observable<UltrasoundOrderResponse> {
    return this.http.post<UltrasoundOrderResponse>(`${this.baseUrl}/orders`, req);
  }

  updateOrder(orderId: string, req: UltrasoundOrderRequest): Observable<UltrasoundOrderResponse> {
    return this.http.put<UltrasoundOrderResponse>(`${this.baseUrl}/orders/${orderId}`, req);
  }

  cancelOrder(orderId: string, cancellationReason?: string): Observable<UltrasoundOrderResponse> {
    let params = new HttpParams();
    if (cancellationReason) params = params.set('cancellationReason', cancellationReason);
    return this.http.post<UltrasoundOrderResponse>(
      `${this.baseUrl}/orders/${orderId}/cancel`,
      null,
      { params },
    );
  }

  getOrder(orderId: string): Observable<UltrasoundOrderResponse> {
    return this.http.get<UltrasoundOrderResponse>(`${this.baseUrl}/orders/${orderId}`);
  }

  ordersByPatient(
    patientId: string,
    status?: UltrasoundOrderStatus,
  ): Observable<UltrasoundOrderResponse[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<UltrasoundOrderResponse[]>(`${this.baseUrl}/orders/patient/${patientId}`, {
      params,
    });
  }

  ordersByHospital(hospitalId: string): Observable<UltrasoundOrderResponse[]> {
    return this.http.get<UltrasoundOrderResponse[]>(
      `${this.baseUrl}/orders/hospital/${hospitalId}`,
    );
  }

  pendingOrders(hospitalId: string): Observable<UltrasoundOrderResponse[]> {
    return this.http.get<UltrasoundOrderResponse[]>(
      `${this.baseUrl}/orders/hospital/${hospitalId}/pending`,
    );
  }

  highRiskOrders(hospitalId: string): Observable<UltrasoundOrderResponse[]> {
    return this.http.get<UltrasoundOrderResponse[]>(
      `${this.baseUrl}/orders/hospital/${hospitalId}/high-risk`,
    );
  }

  /** Creates or updates the report for an order (backend returns 200 either way). */
  submitReport(
    orderId: string,
    req: UltrasoundReportRequest,
  ): Observable<UltrasoundReportResponse> {
    return this.http.post<UltrasoundReportResponse>(
      `${this.baseUrl}/orders/${orderId}/report`,
      req,
    );
  }

  /** DOCTOR/SUPER_ADMIN only. */
  reviewReport(reportId: string): Observable<UltrasoundReportResponse> {
    return this.http.post<UltrasoundReportResponse>(
      `${this.baseUrl}/reports/${reportId}/review`,
      null,
    );
  }

  notifyPatient(reportId: string): Observable<UltrasoundReportResponse> {
    return this.http.post<UltrasoundReportResponse>(
      `${this.baseUrl}/reports/${reportId}/notify-patient`,
      null,
    );
  }

  reportByOrder(orderId: string): Observable<UltrasoundReportResponse> {
    return this.http.get<UltrasoundReportResponse>(`${this.baseUrl}/reports/order/${orderId}`);
  }

  followUpRequired(hospitalId: string): Observable<UltrasoundReportResponse[]> {
    return this.http.get<UltrasoundReportResponse[]>(
      `${this.baseUrl}/reports/hospital/${hospitalId}/follow-up-required`,
    );
  }

  anomalies(hospitalId: string): Observable<UltrasoundReportResponse[]> {
    return this.http.get<UltrasoundReportResponse[]>(
      `${this.baseUrl}/reports/hospital/${hospitalId}/anomalies`,
    );
  }

  /** Prefilled report template for the given scan protocol. */
  template(kind: 'nuchal-translucency' | 'anatomy-scan'): Observable<UltrasoundReportRequest> {
    return this.http.get<UltrasoundReportRequest>(`${this.baseUrl}/templates/${kind}`);
  }
}
