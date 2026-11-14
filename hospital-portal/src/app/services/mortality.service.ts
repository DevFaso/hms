import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { backgroundRequestOptions } from './ws-transport';

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

/**
 * QA D1714: a stillbirth on the register, from a fetal-death event recorded at delivery (no patient
 * account) or from a stillbirth death record no event holds. Each named classification has its flag.
 */
export interface StillbirthRow {
  id: string | null;
  deathRecordId: string | null;
  declaredByDeathRecordId?: string | null;
  deliveryRecordId: string | null;
  motherPatientId: string | null;
  motherName: string | null;
  /** On a death-record row: the stillborn baby's own record, never shown as the mother. */
  patientId?: string | null;
  patientName?: string | null;
  birthOrder: number | null;
  occurredAt: string;
  gestationalAgeWeeks: number | null;
  noSignsOfLife: boolean;
  source: 'DELIVERY' | 'DELIVERY_HISTORICAL' | null;
  recordedByName: string | null;
  whoStillbirth: boolean;
  icdFetalDeath: boolean;
  classificationIncomplete: boolean;
}

export interface MortalityRegister {
  from: string;
  to: string;
  totalDeaths: number;
  /** QA D1714: the death records that are not stillbirths. */
  deathsAfterLiveBirth?: number;
  /** WHO definition — late maternal deaths are counted separately. */
  maternalDeaths: number;
  lateMaternalDeaths: number;
  /** QA D1714 (provisional): WHO stillbirths plus early neonatal deaths (days 0-6). */
  perinatalDeaths: number;
  /** Of perinatalDeaths: early neonatal deaths counted on the clinician's classification, a fact missing. */
  perinatalEligibilityUnknown: number;
  /** Early neonatal deaths with a known gestation under the ICD level: not in perinatalDeaths. */
  perinatalBelowGestation: number;
  /** QA D1714: every stillbirth recorded, whatever the gestation. */
  stillbirths: number;
  /** Of stillbirths: the WHO stillbirth indicator. */
  stillbirthsWho?: number;
  /** Of stillbirths: the broader ICD level. */
  stillbirthsIcd?: number;
  /** Of stillbirths: no gestation recorded, classification incomplete. */
  stillbirthsClassificationIncomplete?: number;
  /** The thresholds the figures use (provisional, set on the server). */
  whoStillbirthMinWeeks?: number;
  icdFetalDeathMinWeeks?: number;
  stillbirthRows?: StillbirthRow[];
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

/** QA D1516: what a death later marked entered in error had closed at this hospital; nothing was reopened. */
export interface VoidedDeathClosures {
  deathRecordId: string;
  enteredInErrorAt: string;
  stays: { admissionId: string; admittedAt: string; closedAt: string | null }[];
  visits: { encounterId: string; visitAt: string | null }[];
  appointments: { appointmentId: string; appointmentDate: string }[];
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

  /** QA D1516: for the chart. QA D1524: a side panel's read; a 403 costs the banner, never the chart. */
  voidedClosures(patientId: string): Observable<VoidedDeathClosures[]> {
    return this.http.get<VoidedDeathClosures[]>(
      `${this.baseUrl}/patients/${patientId}/voided-closures`,
      backgroundRequestOptions(),
    );
  }

  getRegister(from: string, to: string): Observable<MortalityRegister> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<MortalityRegister>(`${this.baseUrl}/register`, { params });
  }
}
