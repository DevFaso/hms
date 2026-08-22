import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { InteractionSeverity } from './medication-timeline.service';

/**
 * Drug-interaction knowledge-base curation (P2 #14).
 *
 * The admin API is the durable half of the KB work — and it had zero callers.
 * NOTE the blast radius: the KB is platform-global (no hospital column), so
 * every write here affects every hospital in the deployment.
 *
 * Codes must be RxNorm RxCUIs (numeric): the CDS-Hooks layer joins on
 * rxnormCode with exact equality, so a free-text "code" produces a row that
 * silently never fires there.
 */

export interface DrugInteractionEntry {
  id?: string;
  drug1Code: string;
  drug1Name: string;
  drug2Code: string;
  drug2Name: string;
  severity: InteractionSeverity;
  description?: string;
  recommendation: string;
  mechanism?: string;
  clinicalEffects?: string;
  /** Derived server-side from severity — read-only in any UI. */
  requiresAvoidance?: boolean;
  requiresDoseAdjustment?: boolean;
  requiresMonitoring?: boolean;
  monitoringParameters?: string;
  monitoringIntervalHours?: number;
  sourceDatabase?: string;
  evidenceLevel?: string;
  literatureReferences?: string;
  /** Curation note: why the row was added, corrected or retired. */
  notes?: string;
  active?: boolean;
}

@Injectable({ providedIn: 'root' })
export class DrugInteractionService {
  private readonly http = inject(HttpClient);

  list(severity?: InteractionSeverity | '', activeOnly = true): Observable<DrugInteractionEntry[]> {
    let params = new HttpParams().set('activeOnly', activeOnly);
    if (severity) {
      params = params.set('severity', severity);
    }
    return this.http.get<DrugInteractionEntry[]>('/drug-interactions', { params });
  }

  create(entry: DrugInteractionEntry): Observable<DrugInteractionEntry> {
    return this.http.post<DrugInteractionEntry>('/drug-interactions', entry);
  }

  update(id: string, entry: DrugInteractionEntry): Observable<DrugInteractionEntry> {
    return this.http.put<DrugInteractionEntry>(`/drug-interactions/${id}`, entry);
  }

  /** Retire, never delete — an entry that has fired is part of what a prescriber was shown. */
  deactivate(id: string): Observable<DrugInteractionEntry> {
    return this.http.put<DrugInteractionEntry>(`/drug-interactions/${id}/deactivate`, {});
  }

  reactivate(id: string): Observable<DrugInteractionEntry> {
    return this.http.put<DrugInteractionEntry>(`/drug-interactions/${id}/reactivate`, {});
  }
}
