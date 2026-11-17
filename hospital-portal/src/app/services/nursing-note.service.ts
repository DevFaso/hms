import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A nursing care note (DAR or SOAPIE) as `GET /nurse/notes` returns it. */
export interface NursingNote {
  id: string;
  patientId: string;
  hospitalId?: string;
  authorName?: string;
  authorCredentials?: string;
  template?: 'DAR' | 'SOAPIE' | string;
  dataSubjective?: string | null;
  dataObjective?: string | null;
  dataAssessment?: string | null;
  dataPlan?: string | null;
  dataImplementation?: string | null;
  dataEvaluation?: string | null;
  actionSummary?: string | null;
  responseSummary?: string | null;
  educationSummary?: string | null;
  narrative?: string | null;
  /** QA D1044: the title the nurse gave the note. */
  title?: string | null;
  lateEntry?: boolean;
  eventOccurredAt?: string | null;
  documentedAt?: string | null;
  signedAt?: string | null;
  signedByName?: string | null;
}

/**
 * QA D927: the nursing care notes the nurse station writes were readable only
 * through the API. This is their reader, for the doctor and the next shift.
 */
@Injectable({ providedIn: 'root' })
export class NursingNoteService {
  private readonly http = inject(HttpClient);

  /** Roles `GET /nurse/notes` admits (NursingNoteController). */
  static readonly READER_ROLES = ['ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_DOCTOR', 'ROLE_SUPER_ADMIN'];

  listRecent(patientId: string, hospitalId?: string, limit = 20): Observable<NursingNote[]> {
    let params = new HttpParams().set('patientId', patientId).set('limit', limit);
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    return this.http.get<NursingNote[]>('/nurse/notes', { params });
  }
}
