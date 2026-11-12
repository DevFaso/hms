import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type LaborStatus =
  'ACTIVE' | 'DELIVERED' | 'TRANSFERRED' | 'CLOSED' | 'TRANSFERRED_OUT' | 'LEFT_WITHOUT_HANDOVER';
export type MembraneStatus = 'INTACT' | 'SPONTANEOUS_RUPTURE' | 'ARTIFICIAL_RUPTURE';
export type LiquorColour =
  'MEMBRANES_INTACT' | 'CLEAR' | 'MECONIUM_STAINED' | 'BLOOD_STAINED' | 'ABSENT';
export type MouldingDegree = 'NONE' | 'PLUS_ONE' | 'PLUS_TWO' | 'PLUS_THREE';
export type DeliveryMode =
  | 'SPONTANEOUS_VAGINAL'
  | 'VACUUM_EXTRACTION'
  | 'FORCEPS'
  | 'CAESAREAN_ELECTIVE'
  | 'CAESAREAN_EMERGENCY'
  | 'ASSISTED_BREECH';
export type InfantSex = 'MALE' | 'FEMALE' | 'UNDETERMINED';
export type PerinealTear =
  'NONE' | 'FIRST_DEGREE' | 'SECOND_DEGREE' | 'THIRD_DEGREE' | 'FOURTH_DEGREE' | 'EPISIOTOMY';

export interface LaborAlert {
  type: string;
  severity: 'INFO' | 'CAUTION' | 'URGENT';
  code: string;
  message: string;
  triggeredBy: string | null;
  createdAt: string;
}

export interface LaborEpisodeRequest {
  hospitalId?: string;
  registrationId?: string;
  admittedByStaffId?: string;
  laborOnsetAt?: string;
  admittedAt?: string;
  membraneStatus?: MembraneStatus;
  membraneRuptureAt?: string;
  gestationalAgeWeeks?: number;
  gravida?: number;
  para?: number;
  riskNotes?: string;
  /** QA D214: required by the server when a birth was recorded recently. */
  recentDeliveryOverrideReason?: string;
}

export interface LaborEpisodeResponse {
  id: string;
  patientId: string;
  patientName: string;
  hospitalId: string;
  /** QA D1631: the hospital that owns the episode; another hospital shows it read only. */
  hospitalName?: string | null;
  registrationId: string | null;
  maternalHistoryId: string | null;
  admittedByStaffName: string | null;
  laborOnsetAt: string | null;
  admittedAt: string;
  membraneStatus: MembraneStatus | null;
  membraneRuptureAt: string | null;
  gestationalAgeWeeks: number | null;
  gravida: number | null;
  para: number | null;
  activePhaseStartAt: string | null;
  status: LaborStatus;
  outcome: string | null;
  riskNotes: string | null;
  entryCount: number;
  deliveryRecorded: boolean;
  /** Labour transfer (provisional rule, 2026-11-12): on a closed episode, when, why and where to. */
  transferredAt?: string | null;
  transferReason?: string | null;
  transferHospitalId?: string | null;
  transferHospitalName?: string | null;
  /** On the episode that took over: the episode it continues, and its hospital. */
  transferredFromEpisodeId?: string | null;
  transferredFromHospitalName?: string | null;
}

/** A labour transfer (with a handover) or an arrival without one: the reason is required. */
export interface LaborTransferRequest {
  /** The hospital taking her over (transfer with a handover only). */
  destinationHospitalId?: string;
  reason: string;
}

export interface PartographEntryRequest {
  hospitalId?: string;
  recordedByStaffId?: string;
  observationTime?: string;
  lateEntry?: boolean;
  fetalHeartRateBpm?: number;
  liquorColour?: LiquorColour;
  mouldingDegree?: MouldingDegree;
  cervicalDilationCm?: number;
  descentFifths?: number;
  contractionsPerTenMinutes?: number;
  contractionDurationSeconds?: number;
  oxytocinDropsPerMinute?: number;
  drugsGiven?: string;
  ivFluids?: string;
  pulseBpm?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  temperatureCelsius?: number;
  urineOutputMl?: number;
  urineProtein?: string;
  urineAcetone?: string;
  notes?: string;
}

export interface PartographEntryResponse {
  id: string;
  episodeId: string;
  patientId: string;
  observationTime: string;
  documentedAt: string;
  lateEntry: boolean;
  recordedByStaffName: string | null;
  fetalHeartRateBpm: number | null;
  liquorColour: LiquorColour | null;
  mouldingDegree: MouldingDegree | null;
  cervicalDilationCm: number | null;
  descentFifths: number | null;
  contractionsPerTenMinutes: number | null;
  contractionDurationSeconds: number | null;
  oxytocinDropsPerMinute: number | null;
  drugsGiven: string | null;
  ivFluids: string | null;
  pulseBpm: number | null;
  systolicBpMmHg: number | null;
  diastolicBpMmHg: number | null;
  temperatureCelsius: number | null;
  urineOutputMl: number | null;
  urineProtein: string | null;
  urineAcetone: string | null;
  notes: string | null;
  alerts: LaborAlert[];
  hoursSinceActivePhaseStart: number | null;
}

export interface DeliveryRecordRequest {
  hospitalId?: string;
  deliveredByStaffId?: string;
  birthDateTime?: string;
  deliveryMode: DeliveryMode;
  liveBirth?: boolean;
  numberOfInfants?: number;
  infantSex?: InfantSex;
  birthWeightGrams?: number;
  gestationalAgeWeeksAtBirth?: number;
  apgarOneMinute?: number;
  apgarFiveMinute?: number;
  placentaDeliveredAt?: string;
  placentaComplete?: boolean;
  uterotonicGiven?: boolean;
  estimatedBloodLossMl?: number;
  perinealTear?: PerinealTear;
  notes?: string;
  recentDeliveryOverrideReason?: string;
}

export interface DeliveryRecordResponse {
  id: string;
  episodeId: string;
  patientId: string;
  deliveredByStaffName: string | null;
  birthDateTime: string;
  deliveryMode: DeliveryMode;
  liveBirth: boolean;
  numberOfInfants: number;
  infantSex: InfantSex | null;
  birthWeightGrams: number | null;
  gestationalAgeWeeksAtBirth: number | null;
  apgarOneMinute: number | null;
  apgarFiveMinute: number | null;
  placentaDeliveredAt: string | null;
  placentaComplete: boolean | null;
  uterotonicGiven: boolean | null;
  estimatedBloodLossMl: number | null;
  perinealTear: PerinealTear | null;
  notes: string | null;
  alerts: LaborAlert[];
}

@Injectable({ providedIn: 'root' })
export class LaborService {
  private readonly http = inject(HttpClient);

  private base(patientId: string): string {
    return `/patients/${patientId}/labor`;
  }

  startEpisode(patientId: string, req: LaborEpisodeRequest): Observable<LaborEpisodeResponse> {
    return this.http.post<LaborEpisodeResponse>(`${this.base(patientId)}/episodes`, req);
  }

  episodes(patientId: string, limit = 10): Observable<LaborEpisodeResponse[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<LaborEpisodeResponse[]>(`${this.base(patientId)}/episodes`, { params });
  }

  addEntry(
    patientId: string,
    episodeId: string,
    req: PartographEntryRequest,
  ): Observable<PartographEntryResponse> {
    return this.http.post<PartographEntryResponse>(
      `${this.base(patientId)}/episodes/${episodeId}/entries`,
      req,
    );
  }

  entries(patientId: string, episodeId: string): Observable<PartographEntryResponse[]> {
    return this.http.get<PartographEntryResponse[]>(
      `${this.base(patientId)}/episodes/${episodeId}/entries`,
    );
  }

  recordDelivery(
    patientId: string,
    episodeId: string,
    req: DeliveryRecordRequest,
  ): Observable<DeliveryRecordResponse> {
    return this.http.post<DeliveryRecordResponse>(
      `${this.base(patientId)}/episodes/${episodeId}/delivery`,
      req,
    );
  }

  /** The episode's own hospital hands the active labour to another hospital, with a handover. */
  transfer(
    patientId: string,
    episodeId: string,
    req: LaborTransferRequest,
  ): Observable<LaborEpisodeResponse> {
    return this.http.post<LaborEpisodeResponse>(
      `${this.base(patientId)}/episodes/${episodeId}/transfer`,
      req,
    );
  }

  /** She arrived here in labour without a handover from the hospital where this episode is active. */
  arrivalWithoutHandover(
    patientId: string,
    episodeId: string,
    req: LaborTransferRequest,
  ): Observable<LaborEpisodeResponse> {
    return this.http.post<LaborEpisodeResponse>(
      `${this.base(patientId)}/episodes/${episodeId}/arrival-without-handover`,
      req,
    );
  }

  delivery(patientId: string, episodeId: string): Observable<DeliveryRecordResponse> {
    return this.http.get<DeliveryRecordResponse>(
      `${this.base(patientId)}/episodes/${episodeId}/delivery`,
    );
  }
}
