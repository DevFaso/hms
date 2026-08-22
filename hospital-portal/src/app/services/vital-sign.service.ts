import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Mirrors the backend PatientVitalSignResponseDTO field-for-field. The old
 * interface here invented its own names (heartRate, temperature, height,
 * painLevel…) that the wire never carried, so the patient-detail vitals tab
 * rendered no metric at all while its specs mocked the wrong shape and stayed
 * green. Field names below are the contract — do not "clean them up".
 */
export interface VitalSignResponse {
  id: string;
  patientId: string;
  registrationId: string | null;
  hospitalId: string | null;
  hospitalName: string | null;
  recordedByStaffId: string | null;
  recordedByAssignmentId: string | null;
  recordedByName: string | null;
  source: string | null;
  temperatureCelsius: number | null;
  heartRateBpm: number | null;
  respiratoryRateBpm: number | null;
  systolicBpMmHg: number | null;
  diastolicBpMmHg: number | null;
  spo2Percent: number | null;
  bloodGlucoseMgDl: number | null;
  weightKg: number | null;
  heightCm: number | null;
  headCircumferenceCm: number | null;
  bodyPosition: string | null;
  notes: string | null;
  clinicallySignificant: boolean;
  recordedAt: string;
  createdAt: string;
  updatedAt: string | null;
}

/** Mirrors PatientVitalSignRequestDTO (measurement subset). */
export interface VitalSignRequest {
  temperatureCelsius?: number;
  heartRateBpm?: number;
  respiratoryRateBpm?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  spo2Percent?: number;
  bloodGlucoseMgDl?: number;
  weightKg?: number;
  heightCm?: number;
  headCircumferenceCm?: number;
  bodyPosition?: string;
  notes?: string;
  recordedAt?: string;
}

/** Mirrors GrowthChartDTO — the patient's own anthropometric trajectory. */
export interface GrowthPoint {
  recordedAt: string;
  ageDays: number;
  weightKg: number | null;
  heightCm: number | null;
  headCircumferenceCm: number | null;
  source: string | null;
}

export interface GrowthChartResponse {
  patientId: string;
  dateOfBirth: string;
  gender: string | null;
  points: GrowthPoint[];
}

@Injectable({ providedIn: 'root' })
export class VitalSignService {
  private readonly http = inject(HttpClient);

  record(patientId: string, req: VitalSignRequest): Observable<VitalSignResponse> {
    return this.http.post<VitalSignResponse>(`/patients/${patientId}/vitals`, req);
  }

  getRecent(patientId: string): Observable<VitalSignResponse[]> {
    return this.http.get<VitalSignResponse[]>(`/patients/${patientId}/vitals/recent`);
  }

  /** The backend returns a bare array (page/size are plain query params, no Page envelope). */
  list(
    patientId: string,
    params?: { page?: number; size?: number; from?: string; to?: string },
  ): Observable<VitalSignResponse[]> {
    let httpParams = new HttpParams();
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size) httpParams = httpParams.set('size', params.size);
    if (params?.from) httpParams = httpParams.set('from', params.from);
    if (params?.to) httpParams = httpParams.set('to', params.to);
    return this.http.get<VitalSignResponse[]>(`/patients/${patientId}/vitals`, {
      params: httpParams,
    });
  }

  getGrowthChart(patientId: string): Observable<GrowthChartResponse> {
    return this.http.get<GrowthChartResponse>(`/patients/${patientId}/growth-chart`);
  }
}
