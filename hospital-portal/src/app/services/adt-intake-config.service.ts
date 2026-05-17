import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Backing client for the per-hospital ADT auto-create defaults
 * (roadmap row 24 admin UI). Calls
 * {@code AdtIntakeProviderConfigController} at
 * {@code /admin/adt-intake-configs}.
 */
export type AdmissionType =
  | 'EMERGENCY'
  | 'ELECTIVE'
  | 'URGENT'
  | 'NEWBORN'
  | 'TRAUMA'
  | 'OBSERVATION';

export type AcuityLevel =
  | 'LEVEL_1_RESUSCITATION'
  | 'LEVEL_2_MODERATE'
  | 'LEVEL_3_URGENT'
  | 'LEVEL_4_NON_URGENT'
  | 'LEVEL_5_REFERRAL';

export type EncounterType =
  | 'OUTPATIENT'
  | 'INPATIENT'
  | 'EMERGENCY'
  | 'AMBULATORY'
  | 'HOME_HEALTH'
  | 'VIRTUAL';

export interface AdtIntakeConfig {
  id: string;
  hospitalId: string;
  hospitalName: string | null;
  admittingProviderId: string;
  departmentId: string | null;
  defaultAssignmentId: string | null;
  defaultAdmissionType: AdmissionType;
  defaultAcuityLevel: AcuityLevel;
  defaultEncounterType: EncounterType;
  defaultChiefComplaint: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdtIntakeConfigRequest {
  hospitalId: string;
  admittingProviderId: string;
  departmentId: string | null;
  defaultAssignmentId: string | null;
  defaultAdmissionType: AdmissionType;
  defaultAcuityLevel: AcuityLevel;
  defaultEncounterType: EncounterType;
  defaultChiefComplaint: string;
  enabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class AdtIntakeConfigService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/admin/adt-intake-configs';

  list(): Observable<AdtIntakeConfig[]> {
    return this.http.get<AdtIntakeConfig[]>(this.baseUrl);
  }

  findByHospital(hospitalId: string): Observable<AdtIntakeConfig[]> {
    return this.http.get<AdtIntakeConfig[]>(this.baseUrl, {
      params: { hospitalId },
    });
  }

  upsert(request: AdtIntakeConfigRequest): Observable<AdtIntakeConfig> {
    return this.http.post<AdtIntakeConfig>(this.baseUrl, request);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
