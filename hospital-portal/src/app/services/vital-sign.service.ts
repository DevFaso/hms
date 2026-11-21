import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { WithheldResult, recordsWithheld } from './patient.service';

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
  /* NEWS2 (P3 #25b) — computed server-side per bundle. When newsComplete is
     false the score covers only what was recorded and newsMissing names the
     gaps; render the incompleteness, never hide it. */
  onOxygen: boolean | null;
  consciousnessLevel: ConsciousnessLevel | null;
  newsScore: number | null;
  newsRiskBand: NewsRiskBand | null;
  newsComplete: boolean | null;
  newsMissing: string[] | null;
  /**
   * Review of #1237: OBSTETRIC or NEWBORN when NEWS2 is not validated for this patient at this reading
   * (server News2Applicability); the score fields are then null and the chart says why.
   */
  newsNotValidated?: 'OBSTETRIC' | 'NEWBORN' | null;
  /* Maternal early warning (policy 2026-11-20, PROVISIONAL, pending a named obstetric clinician's
     sign-off). For a pregnant or postpartum woman the server scores the set and the chart shows this
     in place of « NEWS2 non validé »; the portal computes nothing and always says « provisoire ». */
  /** The additional concerns ticked on the set. */
  maternalConcerns?: string[] | null;
  mewsScore?: number | null;
  /** The level the set is answered at (a concern or a RED add-on alert raises it). */
  mewsTier?: MewsTier | null;
  mewsComplete?: boolean | null;
  mewsMissing?: string[] | null;
  mewsConcerns?: string[] | null;
  /** CRADLE add-on alerts. */
  mewsAlerts?: MewsAlert[] | null;
  /** Medical review within this many minutes (0 = immediate); null at the low level. */
  mewsReviewWithinMinutes?: number | null;
  mewsPostBirthPulse?: boolean | null;
  recordedAt: string;
  createdAt: string;
  updatedAt: string | null;
}

export type ConsciousnessLevel = 'ALERT' | 'NEW_CONFUSION' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';
export type NewsRiskBand = 'LOW' | 'LOW_MEDIUM' | 'MEDIUM' | 'HIGH';
export type MewsTier = 'LOW' | 'LOW_MEDIUM' | 'MEDIUM' | 'HIGH';
export type MewsAlert = 'SEVERE_HYPERTENSION' | 'SHOCK' | 'SEVERE_SHOCK';

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
  /** NEWS2 (P3 #25b): supplemental oxygen at measurement time. */
  onOxygen?: boolean;
  /** NEWS2 (P3 #25b): ACVPU consciousness assessment. */
  consciousnessLevel?: ConsciousnessLevel;
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

  /** QA D504 — `withheld` says the patient's opt-out held other hospitals' vitals back. */
  getRecent(patientId: string): Observable<WithheldResult<VitalSignResponse[]>> {
    return this.http
      .get<VitalSignResponse[]>(`/patients/${patientId}/vitals/recent`, { observe: 'response' })
      .pipe(map((res) => ({ items: res.body ?? [], withheld: recordsWithheld(res.headers) })));
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
