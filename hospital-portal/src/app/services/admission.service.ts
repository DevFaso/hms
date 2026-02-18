import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type AdmissionType = 'EMERGENCY' | 'ELECTIVE' | 'URGENT' | 'TRANSFER' | 'OBSERVATION';
export type AcuityLevel = 'CRITICAL' | 'HIGH' | 'MODERATE' | 'LOW' | 'MINIMAL';
export type AdmissionStatus = 'ADMITTED' | 'DISCHARGED' | 'TRANSFERRED' | 'CANCELLED' | 'PENDING';

export interface AdmissionResponse {
  id: string;
  patientId: string;
  patientName: string;
  hospitalId: string;
  hospitalName: string;
  departmentId: string;
  departmentName: string;
  admittingProviderId: string;
  admittingProviderName: string;
  attendingPhysicianId: string;
  attendingPhysicianName: string;
  roomBed: string;
  admissionType: AdmissionType;
  acuityLevel: AcuityLevel;
  status: AdmissionStatus;
  admissionDateTime: string;
  expectedDischargeDateTime: string;
  actualDischargeDateTime: string;
  chiefComplaint: string;
  primaryDiagnosisCode: string;
  primaryDiagnosisDescription: string;
  admissionSource: string;
  admissionNotes: string;
  insuranceAuthNumber: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdmissionRequest {
  patientId: string;
  hospitalId: string;
  admittingProviderId: string;
  departmentId?: string;
  roomBed?: string;
  admissionType: AdmissionType;
  acuityLevel: AcuityLevel;
  admissionDateTime: string;
  expectedDischargeDateTime?: string;
  chiefComplaint: string;
  primaryDiagnosisCode?: string;
  primaryDiagnosisDescription?: string;
  admissionSource?: string;
  admissionNotes?: string;
  attendingPhysicianId?: string;
  insuranceAuthNumber?: string;
  orderSetIds?: string[];
  customOrders?: Record<string, unknown>[];
}

export interface DischargeApprovalResponse {
  id: string;
  status: string;
  patientId: string;
  patientName: string;
  hospitalId: string;
  hospitalName: string;
  nurseStaffId: string;
  nurseName: string;
  doctorStaffId: string;
  doctorName: string;
  nurseSummary: string;
  doctorNote: string;
  rejectionReason: string;
  requestedAt: string;
  approvedAt: string;
  resolvedAt: string;
  currentStayStatus: string;
}

@Injectable({ providedIn: 'root' })
export class AdmissionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/admissions';

  create(req: AdmissionRequest): Observable<AdmissionResponse> {
    return this.http.post<AdmissionResponse>(this.baseUrl, req);
  }

  getById(id: string): Observable<AdmissionResponse> {
    return this.http.get<AdmissionResponse>(`${this.baseUrl}/${id}`);
  }

  update(id: string, req: Partial<AdmissionRequest>): Observable<AdmissionResponse> {
    return this.http.put<AdmissionResponse>(`${this.baseUrl}/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  discharge(id: string, data: Record<string, unknown>): Observable<AdmissionResponse> {
    return this.http.post<AdmissionResponse>(`${this.baseUrl}/${id}/discharge`, data);
  }

  applyOrderSets(id: string, orderSetIds: string[]): Observable<AdmissionResponse> {
    return this.http.post<AdmissionResponse>(`${this.baseUrl}/${id}/apply-order-sets`, {
      orderSetIds,
    });
  }

  getByPatient(patientId: string): Observable<AdmissionResponse[]> {
    return this.http.get<AdmissionResponse[]>(`${this.baseUrl}/patient/${patientId}`);
  }

  getCurrentByPatient(patientId: string): Observable<AdmissionResponse> {
    return this.http.get<AdmissionResponse>(`${this.baseUrl}/patient/${patientId}/current`);
  }

  getByHospital(
    hospitalId: string,
    params?: { status?: string; startDate?: string; endDate?: string },
  ): Observable<AdmissionResponse[]> {
    let httpParams = new HttpParams();
    if (params?.status) httpParams = httpParams.set('status', params.status);
    if (params?.startDate) httpParams = httpParams.set('startDate', params.startDate);
    if (params?.endDate) httpParams = httpParams.set('endDate', params.endDate);
    return this.http.get<AdmissionResponse[]>(`${this.baseUrl}/hospital/${hospitalId}`, {
      params: httpParams,
    });
  }

  getAll(params?: {
    status?: string;
    startDate?: string;
    endDate?: string;
  }): Observable<AdmissionResponse[]> {
    let httpParams = new HttpParams();
    if (params?.status) httpParams = httpParams.set('status', params.status);
    if (params?.startDate) httpParams = httpParams.set('startDate', params.startDate);
    if (params?.endDate) httpParams = httpParams.set('endDate', params.endDate);
    return this.http.get<AdmissionResponse[]>(this.baseUrl, { params: httpParams });
  }
}
