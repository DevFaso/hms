import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, catchError } from 'rxjs';

export type CdsAcknowledgementAction = 'ACKNOWLEDGED' | 'OVERRIDDEN';

export interface CdsAcknowledgementRequest {
  patientId: string;
  hospitalId?: string | null;
  cardUuid?: string | null;
  cardSummary: string;
  indicator: string;
  action: CdsAcknowledgementAction;
  reason?: string | null;
}

export interface CdsAcknowledgementResponse {
  id: string;
  patientId: string;
  hospitalId?: string | null;
  cardUuid?: string | null;
  cardSummary: string;
  indicator: string;
  action: CdsAcknowledgementAction;
  reason?: string | null;
  createdAt: string;
  expiresAt: string;
}

interface ApiWrapper<T> {
  data: T;
}

@Injectable({ providedIn: 'root' })
export class CdsAcknowledgementService {
  private readonly http = inject(HttpClient);
  private readonly base = '/cds-acknowledgements';

  record(req: CdsAcknowledgementRequest): Observable<CdsAcknowledgementResponse> {
    return this.http
      .post<ApiWrapper<CdsAcknowledgementResponse>>(this.base, req)
      .pipe(map((r) => r.data));
  }

  active(patientId: string): Observable<CdsAcknowledgementResponse[]> {
    const params = new HttpParams().set('patientId', patientId);
    return this.http.get<ApiWrapper<CdsAcknowledgementResponse[]>>(this.base, { params }).pipe(
      map((r) => r.data ?? []),
      catchError(() => of([])),
    );
  }
}
