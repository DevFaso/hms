import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { WithheldResult, recordsWithheld } from './patient.service';

export type EncounterStatus =
  | 'SCHEDULED'
  | 'ARRIVED'
  | 'TRIAGE'
  | 'WAITING_FOR_PHYSICIAN'
  | 'IN_PROGRESS'
  | 'AWAITING_RESULTS'
  | 'READY_FOR_DISCHARGE'
  | 'COMPLETED'
  | 'CANCELLED'
  /** Round 13: the patient left before a clinician saw them; never a consultation. */
  | 'LEFT_WITHOUT_BEING_SEEN';

/**
 * Every closed encounter status (EncounterStatus.CLOSED on the server): a
 * closed visit takes no new order, triage or intake. One list, so a new closed
 * status cannot be missed by one of the open-visit filters.
 */
export const CLOSED_ENCOUNTER_STATUSES: readonly string[] = [
  'COMPLETED',
  'CANCELLED',
  'LEFT_WITHOUT_BEING_SEEN',
];

export function isClosedEncounterStatus(status: string | null | undefined): boolean {
  return !!status && CLOSED_ENCOUNTER_STATUSES.includes(status);
}
/**
 * The eight constants EncounterType.java declares. TELEHEALTH was missing
 * here, so the API could send a value this type said was impossible and the
 * encounters form could not offer it. (adt-intake-config.service.ts declares
 * its own copy with all eight; walkin-dialog a third with five. Three unions
 * for one backend enum is its own debt.)
 */
export type EncounterType =
  | 'CONSULTATION'
  | 'FOLLOW_UP'
  | 'EMERGENCY'
  | 'SURGERY'
  | 'LAB'
  | 'OUTPATIENT'
  | 'INPATIENT'
  | 'TELEHEALTH';

export interface EncounterNoteResponse {
  id: string;
  encounterId: string;
  content: string;
  authorName: string;
  createdAt: string;
  /** QA D831: when the entry was documented (a stay holds one entry per progress note). */
  documentedAt?: string | null;
  authorUserId?: string | null;
  /* Ceremony evidence (P3 #20), present on EncounterNoteResponseDTO payloads
     (encounter.note, addNote/sign/cosign responses). signatureValue null on a
     signed note means "asserted before V125, unverifiable". */
  signedAt?: string | null;
  signedByName?: string | null;
  signatureValue?: string | null;
  requiresCosign?: boolean;
  cosignedAt?: string | null;
  cosignedByName?: string | null;
  /* The note sections (EncounterNoteResponseDTO), shown before a clinician
     signs or co-signs an entry (#965 review). */
  chiefComplaint?: string | null;
  historyOfPresentIllness?: string | null;
  reviewOfSystems?: string | null;
  physicalExam?: string | null;
  diagnosticResults?: string | null;
  subjective?: string | null;
  objective?: string | null;
  assessment?: string | null;
  plan?: string | null;
  implementation?: string | null;
  evaluation?: string | null;
  patientInstructions?: string | null;
}

/**
 * One row of GET /encounters/{id}/notes/history (EncounterNoteHistoryResponseDTO).
 * The note's content at that moment is a JSON string, not note fields: typing
 * this as an EncounterNoteResponse rendered every entry empty (QA D43).
 */
export interface EncounterNoteHistoryEntry {
  id: string;
  noteId?: string | null;
  template?: string | null;
  changedAt: string;
  changedBy?: string | null;
  changeType?: string | null;
  /** JSON object: template, chiefComplaint, subjective, objective, assessment, plan, … */
  contentSnapshot?: string | null;
  /** JSON object: authorName, signedByName, signedAt, … */
  metadataSnapshot?: string | null;
}

export type EncounterUrgency = 'EMERGENT' | 'URGENT' | 'ROUTINE' | 'LOW';

export interface EncounterResponse {
  id: string;
  /** QA D276: the visit code a billing clerk links an invoice to. */
  code?: string;
  patientId: string;
  patientName: string;
  patientEmail: string;
  patientPhoneNumber: string;
  staffId: string;
  staffName: string;
  staffEmail: string;
  departmentId: string;
  departmentName: string;
  hospitalId: string;
  hospitalName: string;
  appointmentId: string;
  appointmentReason: string;
  appointmentNotes: string;
  appointmentStatus: string;
  appointmentType: string;
  appointmentDate: string;
  encounterType: EncounterType;
  status: EncounterStatus;
  encounterDate: string;
  notes: string;
  arrivalTimestamp: string | null;
  chiefComplaint: string;
  esiScore: number | null;
  roomAssignment: string;
  triageTimestamp: string | null;
  roomedTimestamp: string | null;
  urgency: EncounterUrgency | null;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
  note: EncounterNoteResponse;
  /**
   * QA D831: every note entry of the encounter, oldest first, when it has more
   * than one (an inpatient stay's progress notes). `note` is the last of them.
   */
  noteEntries?: EncounterNoteResponse[] | null;
  /** QA D831: set on an inpatient stay, whose notes are progress entries. */
  admissionId?: string | null;
  patientFullName: string;
  staffFullName: string;
  nursingIntakeTimestamp: string | null;
}

export interface TriageSubmissionRequest {
  temperatureCelsius?: number;
  heartRateBpm?: number;
  respiratoryRateBpm?: number;
  systolicBpMmHg?: number;
  diastolicBpMmHg?: number;
  spo2Percent?: number;
  weightKg?: number;
  heightCm?: number;
  /** NEWS2 (P3 #25b): supplemental oxygen at measurement time. */
  onOxygen?: boolean;
  /** NEWS2 (P3 #25b): ACVPU consciousness assessment. */
  consciousnessLevel?: 'ALERT' | 'NEW_CONFUSION' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';
  painScale?: number;
  chiefComplaint?: string;
  esiScore: number;
  fallRisk?: boolean;
  fallRiskScore?: number;
  roomAssignment?: string;
}

export interface TriageSubmissionResponse {
  encounterId: string;
  encounterStatus: EncounterStatus;
  esiScore: number;
  urgency: EncounterUrgency;
  roomAssignment: string;
  triageTimestamp: string;
  roomedTimestamp: string;
  chiefComplaint: string;
  vitalSignId: string;
}

export interface MedicationReconciliationEntry {
  medicationName?: string;
  dosage?: string;
  frequency?: string;
  route?: string;
  stillTaking?: boolean;
  notes?: string;
}

export interface NursingIntakeRequest {
  allergies?: AllergyEntry[];
  medications?: MedicationReconciliationEntry[];
  nursingAssessmentNotes?: string;
  chiefComplaint?: string;
  painAssessment?: string;
  fallRiskDetail?: string;
}

export interface AllergyEntry {
  allergenDisplay: string;
  allergenCode?: string;
  category?: string;
  severity?: string;
  reaction?: string;
  reactionNotes?: string;
}

export interface NursingIntakeResponse {
  encounterId: string;
  encounterStatus: EncounterStatus;
  intakeTimestamp: string;
  allergyCount: number;
  medicationCount: number;
  nursingNoteRecorded: boolean;
}

/* ── MVP 6: Check-Out & AVS ─────────────────────────────────────── */

export interface FollowUpAppointmentRequest {
  reason?: string;
  preferredDate?: string;
  notes?: string;
}

/** QA D788: how a visit no clinician saw ends. */
export type UnseenVisitOutcome = 'LEFT_WITHOUT_BEING_SEEN' | 'CANCELLED';

export interface CloseUnseenVisitRequest {
  outcome: UnseenVisitOutcome;
  reason: string;
}

export interface CheckOutRequest {
  followUpInstructions?: string;
  dischargeDiagnoses?: string[];
  prescriptionSummary?: string;
  referralSummary?: string;
  patientEducationMaterials?: string;
  followUpAppointment?: FollowUpAppointmentRequest;
}

export interface AfterVisitSummary {
  encounterId: string;
  appointmentId?: string;
  visitDate: string;
  providerName: string;
  departmentName: string;
  hospitalName: string;
  patientId?: string;
  patientName?: string;
  chiefComplaint?: string;
  dischargeDiagnoses: string[];
  prescriptionSummary?: string;
  referralSummary?: string;
  followUpInstructions?: string;
  patientEducationMaterials?: string;
  encounterStatus: string;
  appointmentStatus?: string;
  checkoutTimestamp: string;
  followUpAppointmentId?: string;
  followUpAppointmentDate?: string;
}

export interface EncounterRequest {
  patientId: string;
  staffId: string;
  hospitalId: string;
  departmentId?: string;
  appointmentId?: string;
  encounterType: EncounterType;
  encounterDate: string;
  notes?: string;
}

export type EncounterNoteTemplate = 'SOAP' | 'SOAPIE';

/**
 * Per-section EncounterNote payload (item 5). The backend
 * EncounterNoteRequestDTO has carried these columns since SOAP/SOAPIE
 * landed; the FE bound a single textarea to `summary` until this change.
 *
 * All fields except `template` are optional — the FE collapses sections
 * the clinician hasn't expanded so the wire payload stays minimal.
 */
export interface EncounterNoteRequest {
  template: EncounterNoteTemplate;

  /** Free-text summary kept for backwards-compat with the legacy single-textarea flow. */
  summary?: string;

  /** SOAP/SOAPIE chief complaint and visit-context blocks. */
  chiefComplaint?: string;
  historyOfPresentIllness?: string;
  reviewOfSystems?: string;
  physicalExam?: string;
  diagnosticResults?: string;

  /** Core SOAP. */
  subjective?: string;
  objective?: string;
  assessment?: string;
  plan?: string;

  /** SOAPIE-only sections. */
  implementation?: string;
  evaluation?: string;

  patientInstructions?: string;

  /** Late-entry support — when true, eventOccurredAt is the actual visit time. */
  lateEntry?: boolean;
  eventOccurredAt?: string;

  /** Author attestations. */
  attestAccuracy?: boolean;
  attestNoAbbreviations?: boolean;
  attestSpellCheck?: boolean;

  /**
   * Set-only: flags the note for an attending co-signature (P3 #20).
   * The signature itself can no longer be asserted in the note body —
   * the backend refuses signedAt/signedByName here; signing is the
   * POST /encounters/{id}/notes/sign ceremony.
   */
  requiresCosign?: boolean;
}

export interface EncounterNoteAddendumRequest {
  content: string;
  eventOccurredAt?: string;
  attestAccuracy?: boolean;
}

export interface EncounterNoteAddendumResponse {
  id: string;
  content: string;
  eventOccurredAt?: string;
  documentedAt?: string;
  signedAt?: string;
  createdAt: string;
  authorName?: string;
  authorCredentials?: string;
  lateEntry?: boolean;
}

export interface EncounterFilterRequest {
  patientId?: string;
  staffId?: string;
  hospitalId?: string;
  status?: EncounterStatus;
  fromDate?: string;
  toDate?: string;
  /** Page size (GET /encounters is paged, default 20). */
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class EncounterService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/encounters';

  list(filters?: EncounterFilterRequest): Observable<EncounterResponse[]> {
    let params = new HttpParams();
    if (filters) {
      if (filters.patientId) params = params.set('patientId', filters.patientId);
      if (filters.staffId) params = params.set('staffId', filters.staffId);
      if (filters.hospitalId) params = params.set('hospitalId', filters.hospitalId);
      if (filters.status) params = params.set('status', filters.status);
      if (filters.fromDate) params = params.set('from', filters.fromDate);
      if (filters.toDate) params = params.set('to', filters.toDate);
      if (filters.size) params = params.set('size', filters.size);
    }
    return this.http
      .get<{ content: EncounterResponse[] }>(this.baseUrl, { params })
      .pipe(map((res) => res?.content ?? []));
  }

  /**
   * QA D504 — one patient's visit history, with whether the patient's sharing
   * opt-out held other hospitals' encounters back from it.
   */
  listForPatient(patientId: string): Observable<WithheldResult<EncounterResponse[]>> {
    const params = new HttpParams().set('patientId', patientId);
    return this.http
      .get<{ content: EncounterResponse[] }>(this.baseUrl, { params, observe: 'response' })
      .pipe(
        map((res) => ({
          items: res.body?.content ?? [],
          withheld: recordsWithheld(res.headers),
        })),
      );
  }

  getById(id: string): Observable<EncounterResponse> {
    return this.http.get<EncounterResponse>(`${this.baseUrl}/${id}`);
  }

  create(req: EncounterRequest): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(this.baseUrl, req);
  }

  update(id: string, req: EncounterRequest): Observable<EncounterResponse> {
    return this.http.put<EncounterResponse>(`${this.baseUrl}/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  getByDoctor(identifier: string): Observable<EncounterResponse[]> {
    return this.http.get<EncounterResponse[]>(`${this.baseUrl}/doctor/${identifier}`);
  }

  addNote(encounterId: string, req: EncounterNoteRequest): Observable<EncounterNoteResponse> {
    return this.http.post<EncounterNoteResponse>(`${this.baseUrl}/${encounterId}/notes`, req);
  }

  /** Backend contract: EncounterNoteAddendumRequestDTO requires `content`. */
  addAddendum(
    encounterId: string,
    req: EncounterNoteAddendumRequest,
    noteId?: string,
  ): Observable<EncounterNoteAddendumResponse> {
    // QA D831: a stay holds one entry per progress note; noteId names the
    // entry the addendum corrects. Without it the server takes the current entry.
    return this.http.post<EncounterNoteAddendumResponse>(
      `${this.baseUrl}/${encounterId}/notes/addendums`,
      req,
      noteId ? { params: { noteId } } : {},
    );
  }

  getNoteHistory(encounterId: string): Observable<EncounterNoteHistoryEntry[]> {
    return this.http.get<EncounterNoteHistoryEntry[]>(
      `${this.baseUrl}/${encounterId}/notes/history`,
    );
  }

  /** Author-only signing ceremony (P3 #20) — the backend resolves the signer
   *  and refuses non-authors; empty body by design. */
  signNote(encounterId: string, noteId?: string): Observable<EncounterNoteResponse> {
    // QA D831: a stay holds one entry per progress note; noteId names the one
    // on screen. Without it the server takes the current entry only.
    return this.http.post<EncounterNoteResponse>(
      `${this.baseUrl}/${encounterId}/notes/sign`,
      {},
      noteId ? { params: { noteId } } : {},
    );
  }

  /**
   * Attending co-signature — backend refuses the note's own author. noteId
   * names the entry on screen; without it the server takes the current entry.
   */
  cosignNote(encounterId: string, noteId?: string): Observable<EncounterNoteResponse> {
    return this.http.post<EncounterNoteResponse>(
      `${this.baseUrl}/${encounterId}/notes/cosign`,
      {},
      noteId ? { params: { noteId } } : {},
    );
  }

  submitTriage(
    encounterId: string,
    req: TriageSubmissionRequest,
  ): Observable<TriageSubmissionResponse> {
    return this.http.post<TriageSubmissionResponse>(`${this.baseUrl}/${encounterId}/triage`, req);
  }

  submitNursingIntake(
    encounterId: string,
    req: NursingIntakeRequest,
  ): Observable<NursingIntakeResponse> {
    return this.http.post<NursingIntakeResponse>(
      `${this.baseUrl}/${encounterId}/nursing-intake`,
      req,
    );
  }

  checkOut(encounterId: string, req: CheckOutRequest): Observable<AfterVisitSummary> {
    return this.http.post<AfterVisitSummary>(`${this.baseUrl}/${encounterId}/checkout`, req);
  }

  /**
   * QA D788: a visit no clinician has seen is closed as "left without being
   * seen" or cancelled, with a reason; it is never a consultation.
   */
  closeUnseen(encounterId: string, req: CloseUnseenVisitRequest): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(`${this.baseUrl}/${encounterId}/close-unseen`, req);
  }

  startEncounter(encounterId: string): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(`${this.baseUrl}/${encounterId}/start`, {});
  }

  completeTriage(encounterId: string): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(`${this.baseUrl}/${encounterId}/complete-triage`, {});
  }

  completeExamination(encounterId: string): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(
      `${this.baseUrl}/${encounterId}/complete-examination`,
      {},
    );
  }

  markReadyForDischarge(encounterId: string): Observable<EncounterResponse> {
    return this.http.post<EncounterResponse>(
      `${this.baseUrl}/${encounterId}/ready-for-discharge`,
      {},
    );
  }

  /** After-visit summary for a checked-out encounter (JSON document). */
  getAvs(encounterId: string): Observable<AfterVisitSummary> {
    return this.http.get<AfterVisitSummary>(`${this.baseUrl}/${encounterId}/avs`);
  }
}
