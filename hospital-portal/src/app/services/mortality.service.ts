import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type PlaceOfDeath = 'FACILITY' | 'HOME' | 'IN_TRANSIT' | 'OTHER' | 'UNKNOWN';

export type MannerOfDeath =
  'NATURAL' | 'ACCIDENT' | 'SUICIDE' | 'HOMICIDE' | 'UNDETERMINED' | 'PENDING_INVESTIGATION';

export type MaternalDeathTiming =
  'DURING_PREGNANCY' | 'DURING_LABOUR_OR_DELIVERY' | 'WITHIN_42_DAYS_POSTPARTUM' | 'LATE_MATERNAL';

export type PerinatalDeathType = 'STILLBIRTH' | 'EARLY_NEONATAL' | 'LATE_NEONATAL';

export interface DeathRecordResponse {
  id: string;
  patientId: string;
  patientName: string;
  patientMrn: string | null;
  patientDateOfBirth: string | null;
  diedAt: string;
  placeOfDeath: PlaceOfDeath;
  mannerOfDeath: MannerOfDeath;
  immediateCause: string;
  immediateCauseCode: string | null;
  underlyingCause: string | null;
  underlyingCauseCode: string | null;
  contributingCauses: string | null;
  maternalDeath: boolean;
  maternalDeathTiming: MaternalDeathTiming | null;
  /**
   * A maternal death by the WHO definition. False for a LATE_MATERNAL death,
   * which falls outside it and is reported separately.
   */
  whoMaternalDeath: boolean;
  perinatalDeath: boolean;
  perinatalType: PerinatalDeathType | null;
  autopsyRequested: boolean;
  certifiedByName: string | null;
  certifiedAt: string | null;
  amended: boolean;
  amendedAt: string | null;
  amendmentReason: string | null;
  notes: string | null;
  recordedByName: string | null;
  createdAt: string;
}

export interface DeathRecordRequest {
  patientId: string;
  diedAt: string;
  placeOfDeath?: PlaceOfDeath;
  mannerOfDeath?: MannerOfDeath;
  immediateCause: string;
  immediateCauseCode?: string;
  underlyingCause?: string;
  underlyingCauseCode?: string;
  contributingCauses?: string;
  maternalDeath?: boolean;
  /** Required by the backend when maternalDeath is true. */
  maternalDeathTiming?: MaternalDeathTiming;
  perinatalDeath?: boolean;
  /** Required by the backend when perinatalDeath is true. */
  perinatalType?: PerinatalDeathType;
  autopsyRequested?: boolean;
  certifiedByStaffId?: string;
  notes?: string;
}

/** Cannot touch the time of death or the patient — the account is amendable, the fact is not. */
export interface DeathRecordAmendment {
  amendmentReason: string;
  immediateCause?: string;
  immediateCauseCode?: string;
  underlyingCause?: string;
  underlyingCauseCode?: string;
  contributingCauses?: string;
  mannerOfDeath?: MannerOfDeath;
  maternalDeath?: boolean;
  maternalDeathTiming?: MaternalDeathTiming;
  perinatalDeath?: boolean;
  perinatalType?: PerinatalDeathType;
  notes?: string;
}

/** What recording the death actually closed. Reported, never silent. */
export interface DeathClosureSummary {
  admissionsClosed: number;
  encountersClosed: number;
  appointmentsCancelled: number;
  recallsClosed: number;
}

export interface RecordDeathResponse {
  record: DeathRecordResponse;
  closure: DeathClosureSummary;
}

export interface MortalityRegister {
  from: string;
  to: string;
  totalDeaths: number;
  /** WHO definition — late maternal deaths are counted separately. */
  maternalDeaths: number;
  lateMaternalDeaths: number;
  perinatalDeaths: number;
  stillbirths: number;
  deaths: DeathRecordResponse[];
}

@Injectable({ providedIn: 'root' })
export class MortalityService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/mortality';

  recordDeath(req: DeathRecordRequest): Observable<RecordDeathResponse> {
    return this.http.post<RecordDeathResponse>(`${this.baseUrl}/deaths`, req);
  }

  amendDeathRecord(recordId: string, req: DeathRecordAmendment): Observable<DeathRecordResponse> {
    return this.http.post<DeathRecordResponse>(`${this.baseUrl}/deaths/${recordId}/amend`, req);
  }

  getForPatient(patientId: string): Observable<DeathRecordResponse> {
    return this.http.get<DeathRecordResponse>(`${this.baseUrl}/deaths/patient/${patientId}`);
  }

  getRegister(from: string, to: string): Observable<MortalityRegister> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<MortalityRegister>(`${this.baseUrl}/register`, { params });
  }
}
