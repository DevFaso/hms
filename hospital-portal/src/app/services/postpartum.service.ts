import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type PostpartumSchedulePhase =
  | 'IMMEDIATE_RECOVERY'
  | 'SHIFT_BASELINE'
  | 'ENHANCED_MONITORING'
  | 'DISCHARGE_PLANNING';
export type PostpartumFundusTone =
  | 'FIRM'
  | 'SLIGHTLY_BOGGY'
  | 'BOGGY'
  | 'DEVIATED'
  | 'NOT_PALPABLE'
  | 'UNKNOWN';
export type PostpartumBladderStatus =
  | 'VOIDED_SPONTANEOUSLY'
  | 'VOIDED_WITH_ASSISTANCE'
  | 'DISTENDED'
  | 'CATHETER_IN_PLACE'
  | 'NEEDS_STRAIGHT_CATHETERIZATION'
  | 'UNABLE_TO_VOID'
  | 'UNKNOWN';
export type PostpartumLochiaAmount =
  | 'NONE'
  | 'SCANT'
  | 'LIGHT'
  | 'MODERATE'
  | 'HEAVY'
  | 'EXCESSIVE';
export type PostpartumLochiaCharacter =
  | 'RUBRA'
  | 'SEROSA'
  | 'ALBA'
  | 'BROWN_TINGED'
  | 'FRESH_RED'
  | 'FOUL_ODOR'
  | 'WITH_CLOTS'
  | 'OTHER';
export type PostpartumMoodStatus =
  | 'CALM'
  | 'CONTENT'
  | 'ANXIOUS'
  | 'DEPRESSED'
  | 'TEARFUL'
  | 'IRRITABLE'
  | 'WITHDRAWN'
  | 'EUPHORIC'
  | 'OTHER';
export type PostpartumSupportStatus = 'ROBUST' | 'ADEQUATE' | 'LIMITED' | 'NONE' | 'UNKNOWN';
export type PostpartumSleepQuality =
  | 'RESTED'
  | 'ADEQUATE'
  | 'INTERRUPTED'
  | 'EXHAUSTED'
  | 'UNKNOWN';
export type PostpartumAlertSeverity = 'INFO' | 'CAUTION' | 'URGENT';

export interface PostpartumAlert {
  type: string;
  severity: PostpartumAlertSeverity;
  code?: string;
  message: string;
  triggeredBy?: string;
  createdAt?: string;
}

export interface PostpartumSchedule {
  carePlanId?: string;
  phase: PostpartumSchedulePhase;
  immediateWindowComplete: boolean;
  immediateChecksCompleted: number;
  immediateCheckTarget: number;
  frequencyMinutes?: number | null;
  nextDueAt?: string | null;
  overdueSince?: string | null;
  overdue: boolean;
}

export interface PostpartumObservationRequest {
  carePlanId?: string;
  hospitalId?: string;
  recordedByStaffId?: string;
  observationTime?: string;
  temperatureCelsius?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  pulseBpm?: number;
  respirationsPerMin?: number;
  painScore?: number;
  fundusHeightCm?: number;
  fundusTone?: PostpartumFundusTone;
  bladderStatus?: PostpartumBladderStatus;
  lochiaAmount?: PostpartumLochiaAmount;
  lochiaCharacter?: PostpartumLochiaCharacter;
  lochiaNotes?: string;
  perineumFindings?: string;
  uterineAtonySuspected?: boolean;
  excessiveBleeding?: boolean;
  estimatedBloodLossMl?: number;
  uterotonicGiven?: boolean;
  hemorrhageProtocolActivated?: boolean;
  foulLochiaOdor?: boolean;
  uterineTenderness?: boolean;
  chillsOrRigors?: boolean;
  moodStatus?: PostpartumMoodStatus;
  supportStatus?: PostpartumSupportStatus;
  sleepStatus?: PostpartumSleepQuality;
  psychosocialNotes?: string;
  signoffName?: string;
  signoffCredentials?: string;
}

export interface PostpartumObservationResponse {
  id: string;
  patientId: string;
  hospitalId?: string;
  carePlanId?: string;
  observationTime?: string;
  documentedAt?: string;
  temperatureCelsius?: number | null;
  systolicBpMmHg?: number | null;
  diastolicBpMmHg?: number | null;
  pulseBpm?: number | null;
  respirationsPerMin?: number | null;
  painScore?: number | null;
  fundusHeightCm?: number | null;
  fundusTone?: PostpartumFundusTone | null;
  bladderStatus?: PostpartumBladderStatus | null;
  lochiaAmount?: PostpartumLochiaAmount | null;
  lochiaCharacter?: PostpartumLochiaCharacter | null;
  lochiaNotes?: string;
  perineumFindings?: string;
  uterineAtonySuspected?: boolean;
  excessiveBleeding?: boolean;
  estimatedBloodLossMl?: number | null;
  uterotonicGiven?: boolean;
  hemorrhageProtocolActivated?: boolean;
  foulLochiaOdor?: boolean;
  uterineTenderness?: boolean;
  chillsOrRigors?: boolean;
  moodStatus?: PostpartumMoodStatus | null;
  supportStatus?: PostpartumSupportStatus | null;
  sleepStatus?: PostpartumSleepQuality | null;
  psychosocialNotes?: string;
  signoffName?: string;
  signoffCredentials?: string;
  signedAt?: string | null;
  schedulePhaseAtEntry?: PostpartumSchedulePhase | null;
  schedule?: PostpartumSchedule | null;
  alerts?: PostpartumAlert[];
}

/* ── Newborn assessments ── */

export type NewbornFollowUpAction =
  | 'NICU_CONSULT'
  | 'PEDIATRICIAN_NOTIFICATION'
  | 'RESPIRATORY_SUPPORT'
  | 'GLUCOSE_MONITORING'
  | 'THERMAL_SUPPORT'
  | 'SEPSIS_EVALUATION'
  | 'OXYGEN_THERAPY'
  | 'FEEDING_SUPPORT'
  | 'MONITORING_RECHECK'
  | 'PARENT_EDUCATION_REINFORCEMENT';

/** Same shape as maternal alerts — kept as an alias, not a duplicate. */
export type NewbornAlert = PostpartumAlert;

export interface NewbornAssessmentRequest {
  hospitalId?: string;
  recordedByStaffId?: string;
  /**
   * The delivery this newborn came from, when one was recorded in Labor &
   * Delivery. Optional — a newborn may be transferred in already born.
   */
  deliveryRecordId?: string;
  assessmentTime?: string;
  apgarOneMinute?: number;
  apgarFiveMinute?: number;
  apgarTenMinute?: number;
  apgarNotes?: string;
  temperatureCelsius?: number;
  heartRateBpm?: number;
  respirationsPerMin?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  oxygenSaturationPercent?: number;
  glucoseMgDl?: number;
  examGeneralAppearance?: string;
  examNotes?: string;
  followUpNotes?: string;
  followUpActions?: NewbornFollowUpAction[];
  parentEducationNotes?: string;
  parentEducationCompleted?: boolean;
  escalationRecommended?: boolean;
  respiratorySupportInitiated?: boolean;
  glucoseProtocolInitiated?: boolean;
  thermoregulationSupportInitiated?: boolean;
}

export interface NewbornAssessmentResponse {
  id: string;
  patientId: string;
  hospitalId?: string;
  deliveryRecordId?: string | null;
  assessmentTime?: string;
  documentedAt?: string;
  apgarOneMinute?: number | null;
  apgarFiveMinute?: number | null;
  apgarTenMinute?: number | null;
  apgarNotes?: string;
  temperatureCelsius?: number | null;
  heartRateBpm?: number | null;
  respirationsPerMin?: number | null;
  systolicBpMmHg?: number | null;
  diastolicBpMmHg?: number | null;
  oxygenSaturationPercent?: number | null;
  glucoseMgDl?: number | null;
  examGeneralAppearance?: string;
  examNotes?: string;
  escalationRecommended?: boolean;
  respiratorySupportInitiated?: boolean;
  glucoseProtocolInitiated?: boolean;
  thermoregulationSupportInitiated?: boolean;
  followUpNotes?: string;
  followUpActions?: NewbornFollowUpAction[];
  parentEducationNotes?: string;
  parentEducationCompleted?: boolean;
  alerts?: NewbornAlert[];
}

/**
 * Postpartum observations + newborn assessments —
 * /patients/{patientId}/postpartum[...]. Bare DTOs; the "paginated" list
 * endpoints return plain arrays. Effective roles for everything:
 * NURSE/MIDWIFE/DOCTOR/HOSPITAL_ADMIN/SUPER_ADMIN. Auth failures surface as
 * 400 (BusinessException), not 401/403.
 */
@Injectable({ providedIn: 'root' })
export class PostpartumService {
  private readonly http = inject(HttpClient);

  private base(patientId: string): string {
    return `/patients/${patientId}/postpartum`;
  }

  createObservation(
    patientId: string,
    req: PostpartumObservationRequest,
  ): Observable<PostpartumObservationResponse> {
    return this.http.post<PostpartumObservationResponse>(
      `${this.base(patientId)}/observations`,
      req,
    );
  }

  recentObservations(patientId: string, limit = 10): Observable<PostpartumObservationResponse[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<PostpartumObservationResponse[]>(
      `${this.base(patientId)}/observations/recent`,
      { params },
    );
  }

  schedule(patientId: string): Observable<PostpartumSchedule> {
    return this.http.get<PostpartumSchedule>(`${this.base(patientId)}/schedule`);
  }

  createNewbornAssessment(
    patientId: string,
    req: NewbornAssessmentRequest,
  ): Observable<NewbornAssessmentResponse> {
    return this.http.post<NewbornAssessmentResponse>(
      `${this.base(patientId)}/newborn-assessments`,
      req,
    );
  }

  recentNewbornAssessments(patientId: string, limit = 10): Observable<NewbornAssessmentResponse[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<NewbornAssessmentResponse[]>(
      `${this.base(patientId)}/newborn-assessments/recent`,
      { params },
    );
  }
}
