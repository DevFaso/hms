import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Inbound draft identity the receptionist enters into the
 * candidate-confirm form. Mirrors the backend
 * {@code EmpiCandidateQueryDTO} record shape — every field optional
 * because the scorer weighs whatever is present.
 */
export interface EmpiCandidateQuery {
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  sex: string | null;
  nationalId: string | null;
}

/**
 * Single ranked candidate returned by the EMPI scorer. The
 * per-field booleans drive the "name agrees, sex disagrees"
 * breakdown that helps the receptionist decide whether to confirm
 * the match or create a new patient.
 */
export interface EmpiCandidateMatch {
  patientId: string;
  displayName: string;
  score: number;
  nameMatched: boolean;
  dobMatched: boolean;
  sexMatched: boolean;
  nationalIdMatched: boolean;
}

@Injectable({ providedIn: 'root' })
export class EmpiService {
  private readonly http = inject(HttpClient);

  /**
   * POST /api/empi/candidates — server returns ranked candidates
   * whose composite score is at-or-above the configured minScore.
   * Returns an empty array when the flag is off (server 404, which
   * the global interceptor surfaces here — caller renders the
   * "EMPI matching disabled" empty state rather than an error toast).
   */
  findCandidates(query: EmpiCandidateQuery): Observable<EmpiCandidateMatch[]> {
    return this.http.post<EmpiCandidateMatch[]>('/empi/candidates', query);
  }
}
