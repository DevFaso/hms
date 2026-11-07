import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type PlaceOfDeath = 'FACILITY' | 'HOME' | 'IN_TRANSIT' | 'OTHER' | 'UNKNOWN';

export type MannerOfDeath =
  'NATURAL' | 'ACCIDENT' | 'SUICIDE' | 'HOMICIDE' | 'UNDETERMINED' | 'PENDING_INVESTIGATION';

export type MaternalDeathTiming =
  'DURING_PREGNANCY' | 'DURING_LABOUR_OR_DELIVERY' | 'WITHIN_42_DAYS_POSTPARTUM' | 'LATE_MATERNAL';

export type PerinatalDeathType = 'STILLBIRTH' | 'EARLY_NEONATAL' | 'LATE_NEONATAL';

export type PerinatalBirthStatus = 'LIVE_BIRTH' | 'STILLBIRTH' | 'UNKNOWN';

export type PerinatalAgeBasis = 'ELAPSED_TIME' | 'DATE_ONLY' | 'DATE_ONLY_BOUNDARY' | 'UNKNOWN';

export type PerinatalReportingEligibility = 'ELIGIBLE' | 'NOT_ELIGIBLE' | 'UNKNOWN';

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
  bornAt?: string | null;
  birthStatus?: PerinatalBirthStatus;
  gestationalAgeWeeks?: number | null;
  /** What the age at death rests on: elapsed time, dates only, or unknown. */
  perinatalAgeBasis?: PerinatalAgeBasis;
  /** Measured against WHO's perinatal definition. Null with no perinatal classification. */
  perinatalEligibility?: PerinatalReportingEligibility | null;
  autopsyRequested: boolean;
  certifiedByName: string | null;
  certifiedAt: string | null;
  amended: boolean;
  amendedAt: string | null;
  amendmentReason: string | null;
  notes: string | null;
  recordedByName: string | null;
  createdAt: string;
  /** QA D1488: marked entered in error, with who, when and why. Such a row counts as no death. */
  /** Under an open dispute: still counted, until the outcome. */
  disputed?: boolean;
  enteredInError?: boolean;
  enteredInErrorAt?: string | null;
  enteredInErrorByName?: string | null;
  enteredInErrorReason?: string | null;
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
  bornAt?: string;
  birthStatus?: PerinatalBirthStatus;
  gestationalAgeWeeks?: number;
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
  bornAt?: string;
  birthStatus?: PerinatalBirthStatus;
  gestationalAgeWeeks?: number;
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
  deathRecord: DeathRecordResponse;
  closure: DeathClosureSummary;
}

export interface MortalityRegister {
  from: string;
  to: string;
  totalDeaths: number;
  /** WHO definition — late maternal deaths are counted separately. */
  maternalDeaths: number;
  lateMaternalDeaths: number;
  /** WHO definition (provisional): stillbirths and days 0-6, without a known gestation under 22 weeks. */
  perinatalDeaths: number;
  /** Of perinatalDeaths: counted on the clinician's classification, with a fact it rests on missing. */
  perinatalEligibilityUnknown: number;
  /** Known gestation under 22 weeks: not in perinatalDeaths. */
  perinatalBelowGestation: number;
  stillbirths: number;
  earlyNeonatalDeaths: number;
  /** Days 7-27: past the perinatal period, in no perinatal figure. */
  lateNeonatalDeaths: number;
  deaths: DeathRecordResponse[];
  /** QA D1488: kept and shown, in none of the counts above. */
  enteredInError?: DeathRecordResponse[];
  disputedDeaths?: number;
}

export interface DeathDispute {
  id: string;
  patientId: string;
  patientName: string | null;
  patientMrn: string | null;
  hospitalId: string;
  status: 'OPEN' | 'UPHELD' | 'ENTERED_IN_ERROR';
  reason: string;
  disputedByName: string | null;
  disputedAt: string;
  /** Set only at the hospital that recorded the death, which alone can mark the record entered in error. */
  deathRecordId: string | null;
  statusMessage: string | null;
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

  markEnteredInError(recordId: string, reason: string): Observable<DeathRecordResponse> {
    return this.http.post<DeathRecordResponse>(
      `${this.baseUrl}/deaths/${recordId}/entered-in-error`,
      { reason },
    );
  }

  disputeDeath(
    patientId: string,
    body: { reason: string; assessedAlive: boolean; identityVerified: boolean },
  ): Observable<DeathDispute> {
    return this.http.post<DeathDispute>(`${this.baseUrl}/patients/${patientId}/disputes`, body);
  }

  openDisputes(): Observable<DeathDispute[]> {
    return this.http.get<DeathDispute[]>(`${this.baseUrl}/disputes`);
  }

  upholdDeath(disputeId: string, reason: string): Observable<DeathDispute> {
    return this.http.post<DeathDispute>(`${this.baseUrl}/disputes/${disputeId}/uphold`, { reason });
  }

  getForPatient(patientId: string): Observable<DeathRecordResponse> {
    return this.http.get<DeathRecordResponse>(`${this.baseUrl}/deaths/patient/${patientId}`);
  }

  getRegister(from: string, to: string): Observable<MortalityRegister> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<MortalityRegister>(`${this.baseUrl}/register`, { params });
  }
}
