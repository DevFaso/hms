import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

export interface BreakGlassSession {
  id: string;
  patientId: string;
  userId: string;
  userName: string;
  hospitalId: string;
  hospitalName: string;
  reason: string;
  startedAt: string;
  expiresAt: string;
  revokedAt: string | null;
  revokedByUserId: string | null;
  revokeReason: string | null;
  auditCount: number;
  live: boolean;
}

export interface BreakGlassDeclareRequest {
  patientId: string;
  hospitalId: string;
  reason: string;
  ttlMinutes?: number;
}

export interface BreakGlassRevokeRequest {
  reason?: string;
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
}

/**
 * Client wrapper around the /break-glass REST endpoints.
 *
 * `findMyLiveSession` returns null when the server responds 204 No Content,
 * so callers can render the "Declare break-glass" button without an error
 * branch for the no-active-session case.
 */
@Injectable({ providedIn: 'root' })
export class BreakGlassService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/break-glass';

  declare(request: BreakGlassDeclareRequest): Observable<BreakGlassSession> {
    return this.http.post<BreakGlassSession>(this.baseUrl, request);
  }

  revoke(sessionId: string, request: BreakGlassRevokeRequest = {}): Observable<BreakGlassSession> {
    return this.http.post<BreakGlassSession>(`${this.baseUrl}/${sessionId}/revoke`, request);
  }

  listLiveForPatient(patientId: string): Observable<BreakGlassSession[]> {
    const params = new HttpParams().set('patientId', patientId);
    return this.http.get<BreakGlassSession[]>(`${this.baseUrl}/active`, { params });
  }

  findMyLiveSession(patientId: string): Observable<BreakGlassSession | null> {
    const params = new HttpParams().set('patientId', patientId);
    return this.http
      .get<BreakGlassSession>(`${this.baseUrl}/me`, { params, observe: 'response' })
      .pipe(
        map((res) => (res.status === 204 ? null : (res.body ?? null))),
        catchError(() => of(null)),
      );
  }

  listForHospital(
    hospitalId: string,
    page = 0,
    size = 20,
  ): Observable<PageResult<BreakGlassSession>> {
    const params = new HttpParams()
      .set('hospitalId', hospitalId)
      .set('page', page)
      .set('size', size);
    return this.http.get<PageResult<BreakGlassSession>>(`${this.baseUrl}/audit`, { params });
  }
}
