import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/*
 * Standardized patient-reported-outcome instruments (Tier 2 item 47 — EPDS
 * first). Mirrors payload/dto/pro/* on the backend; every endpoint returns
 * the bare DTO (no ApiResponseWrapper), like the ROI workflow.
 *
 * Nothing here knows an item's wording or an option's score: instruments are
 * data loaded from a validated source, and the score of an answer never
 * reaches the browser. The renderer shows labels; the server adds them up.
 */

export type ProResponseSource = 'STAFF_ADMINISTERED' | 'PATIENT_REPORTED';

/** One row of GET /pro-instruments — an instrument with text loaded. */
export interface ProInstrumentSummary {
  code: string;
  name: string;
  version?: string | null;
  languages: string[];
}

export interface ProInstrumentOption {
  optionNo: number;
  label: string;
}

export interface ProInstrumentItem {
  itemNo: number;
  prompt: string;
  options: ProInstrumentOption[];
}

/** An instrument rendered in one language, scores stripped. */
export interface ProInstrumentView {
  code: string;
  name: string;
  version?: string | null;
  sourceCitation?: string | null;
  licenceNote?: string | null;
  /** The language actually served — English when the requested one is not loaded. */
  language: string;
  availableLanguages: string[];
  instruction?: string | null;
  maxScore: number;
  criticalItemNo?: number | null;
  items: ProInstrumentItem[];
}

/** Answers keyed by item number → option number. */
export type ProAnswers = Record<number, number>;

export interface ProResponseCreateRequest {
  instrumentCode: string;
  language?: string;
  answers: ProAnswers;
  /** Local date-time; defaults to now on the server. */
  administeredAt?: string;
  notes?: string;
  hospitalId?: string;
}

export interface ProResponse {
  id: string;
  instrumentCode: string;
  instrumentName?: string;
  patientId: string;
  hospitalId?: string;
  carePlanId?: string | null;
  source: ProResponseSource;
  language?: string;
  administeredAt: string;
  recordedByUserId?: string | null;
  answers: ProAnswers;
  notes?: string | null;
  totalScore: number;
  maxScore: number;
  answeredItems: number;
  totalItems: number;
  complete: boolean;
  screenPositive: boolean;
  criticalItemScore?: number | null;
  criticalItemPositive: boolean;
  escalationLevel: number;
  acknowledgedAt?: string | null;
  acknowledgedByDisplay?: string | null;
  acknowledgementNote?: string | null;
}

/** The cadence hook on a postpartum schedule — is a screen due, what did the last one say. */
export interface ProScreeningSummary {
  instrumentCode: string;
  instrumentAvailable: boolean;
  due: boolean;
  lastResponseId?: string | null;
  lastAdministeredAt?: string | null;
  lastTotalScore?: number | null;
  maxScore?: number | null;
  lastScreenPositive?: boolean | null;
  lastCriticalItemPositive?: boolean | null;
  escalationOpen: boolean;
}

/* ── Patient self-report (/me/patient) ── */

export interface ProSelfReportAvailable {
  code: string;
  name: string;
  languages: string[];
}

/**
 * A screening the patient answered. Carries no score by design — a number
 * without a conversation is not something to hand a new mother at 3 a.m.
 */
export interface ProSelfReportEntry {
  id: string;
  instrumentCode: string;
  instrumentName?: string;
  administeredAt: string;
  followUpPlanned: boolean;
  careTeamAlerted: boolean;
}

export interface ProSelfReport {
  available: ProSelfReportAvailable[];
  history: ProSelfReportEntry[];
}

@Injectable({ providedIn: 'root' })
export class ProScreeningService {
  private readonly http = inject(HttpClient);

  /* ── Staff ── */

  instruments(): Observable<ProInstrumentSummary[]> {
    return this.http.get<ProInstrumentSummary[]>('/pro-instruments');
  }

  instrument(code: string, language?: string): Observable<ProInstrumentView> {
    return this.http.get<ProInstrumentView>(`/pro-instruments/${encodeURIComponent(code)}`, {
      params: language ? new HttpParams().set('language', language) : undefined,
    });
  }

  record(patientId: string, req: ProResponseCreateRequest): Observable<ProResponse> {
    return this.http.post<ProResponse>(`/patients/${patientId}/pro-responses`, req);
  }

  history(patientId: string, instrument?: string, limit = 20): Observable<ProResponse[]> {
    let params = new HttpParams().set('limit', String(limit));
    if (instrument) params = params.set('instrument', instrument);
    return this.http.get<ProResponse[]>(`/patients/${patientId}/pro-responses`, { params });
  }

  acknowledge(
    patientId: string,
    responseId: string,
    actionTaken?: string,
  ): Observable<ProResponse> {
    return this.http.post<ProResponse>(
      `/patients/${patientId}/pro-responses/${responseId}/acknowledge`,
      { actionTaken: actionTaken?.trim() || null },
    );
  }

  /* ── Patient ── */

  myScreenings(): Observable<ProSelfReport> {
    return this.http.get<ProSelfReport>('/me/patient/pro-screenings');
  }

  myInstrument(code: string, language?: string): Observable<ProInstrumentView> {
    return this.http.get<ProInstrumentView>(
      `/me/patient/pro-instruments/${encodeURIComponent(code)}`,
      { params: language ? new HttpParams().set('language', language) : undefined },
    );
  }

  submitMine(req: ProResponseCreateRequest): Observable<ProSelfReportEntry> {
    return this.http.post<ProSelfReportEntry>('/me/patient/pro-screenings', req);
  }
}
