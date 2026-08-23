import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, catchError } from 'rxjs';

export type RefillStatus =
  'REQUESTED' | 'PAUSED' | 'APPROVED' | 'DENIED' | 'DISPENSED' | 'CANCELLED';

export interface RefillRequest {
  id: string;
  prescriptionId: string;
  medicationName: string | null;
  patientId: string;
  status: RefillStatus;
  preferredPharmacy: string | null;
  notes: string | null;
  providerNotes: string | null;
  requestedAt: string | null;
  updatedAt: string | null;
}

export interface RefillDecisionRequest {
  providerNotes?: string;
}

interface ApiWrapper<T> {
  data: T;
  errors?: unknown;
}

interface PageEnvelope<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

/**
 * Wraps the {@code /api/refills} provider-facing endpoints used by the
 * doctor's refill-approval queue. Pairs with {@link PatientPortalService}
 * which exposes the patient-facing flow.
 */
@Injectable({ providedIn: 'root' })
export class RefillApprovalService {
  private readonly http = inject(HttpClient);
  private readonly base = '/refills';

  list(status?: RefillStatus, page = 0, size = 20): Observable<PageEnvelope<RefillRequest>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    return this.http
      .get<ApiWrapper<PageEnvelope<RefillRequest>>>(this.base, { params })
      .pipe(map((r) => r.data));
  }

  pendingCount(): Observable<number> {
    return this.http.get<ApiWrapper<{ pendingCount: number }>>(`${this.base}/pending-count`).pipe(
      map((r) => r.data?.pendingCount ?? 0),
      catchError(() => of(0)),
    );
  }

  approve(refillId: string, decision: RefillDecisionRequest = {}): Observable<RefillRequest> {
    return this.http
      .put<ApiWrapper<RefillRequest>>(`${this.base}/${refillId}/approve`, decision)
      .pipe(map((r) => r.data));
  }

  reject(refillId: string, decision: RefillDecisionRequest = {}): Observable<RefillRequest> {
    return this.http
      .put<ApiWrapper<RefillRequest>>(`${this.base}/${refillId}/reject`, decision)
      .pipe(map((r) => r.data));
  }

  /** Defers a decision. The backend requires `providerNotes` here — it relays them to the patient. */
  pause(refillId: string, decision: Required<RefillDecisionRequest>): Observable<RefillRequest> {
    return this.http
      .put<ApiWrapper<RefillRequest>>(`${this.base}/${refillId}/pause`, decision)
      .pipe(map((r) => r.data));
  }
}
