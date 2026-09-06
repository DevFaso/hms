import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, shareReplay, tap, throwError } from 'rxjs';

/** Minimal hospital DTO returned by /me/hospital. */
export interface HospitalMinimal {
  id: string;
  name: string;
}

export interface HospitalRequest {
  name: string;
  address?: string;
  city: string;
  state?: string;
  zipCode?: string;
  country: string;
  province?: string;
  region?: string;
  sector?: string;
  poBox?: string;
  phoneNumber: string;
  email?: string;
  website?: string;
  organizationId?: string;
  active?: boolean;
}

export interface HospitalResponse {
  id: string;
  name: string;
  code: string;
  address: string;
  city: string;
  state: string;
  zipCode: string;
  country: string;
  province: string;
  region: string;
  sector: string;
  poBox: string;
  phoneNumber: string;
  email: string;
  website: string;
  organizationId: string;
  organizationName: string;
  organizationCode: string;
  active: boolean;
  /**
   * MVP-c2-frontend — backend now exposes the lifecycle state on the
   * Hospital DTO so the list view can render a state chip per row
   * without an N+1 lookup. Optional for backwards compatibility.
   */
  lifecycleState?: 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED' | 'PURGE_SCHEDULED' | 'PURGED';
  createdAt: string;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class HospitalService {
  private readonly http = inject(HttpClient);

  list(filters?: {
    organizationId?: string;
    city?: string;
    state?: string;
    unassignedOnly?: boolean;
  }): Observable<HospitalResponse[]> {
    let params = new HttpParams();
    if (filters?.organizationId) params = params.set('organizationId', filters.organizationId);
    if (filters?.city) params = params.set('city', filters.city);
    if (filters?.state) params = params.set('state', filters.state);
    if (filters?.unassignedOnly) params = params.set('unassignedOnly', 'true');
    return this.http.get<HospitalResponse[]>('/hospitals', { params });
  }

  getById(id: string): Observable<HospitalResponse> {
    return this.http.get<HospitalResponse>(`/hospitals/${id}`);
  }

  create(req: HospitalRequest): Observable<HospitalResponse> {
    return this.http
      .post<HospitalResponse>('/hospitals', req)
      .pipe(tap(() => this.forgetFirstPage()));
  }

  update(id: string, req: HospitalRequest): Observable<HospitalResponse> {
    return this.http
      .put<HospitalResponse>(`/hospitals/${id}`, req)
      .pipe(tap(() => this.forgetFirstPage()));
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/hospitals/${id}`).pipe(tap(() => this.forgetFirstPage()));
  }

  /**
   * Tenant-safe: returns the current user's assigned hospital via GET /me/hospital.
   * This endpoint is permitted for HOSPITAL_ADMIN, RECEPTIONIST, and other non-admin roles,
   * unlike GET /hospitals/{id} which is restricted to SUPER_ADMIN.
   */
  getMyHospital(): Observable<HospitalMinimal> {
    return this.http.get<HospitalMinimal>('/me/hospital');
  }

  /**
   * Tenant-safe hospital fetch that works for any role:
   * - Returns the minimal hospital DTO from /me/hospital
   * - Maps it into a partial HospitalResponse so callers that expect
   *   HospitalResponse can use it for id + name display.
   */
  getMyHospitalAsResponse(): Observable<HospitalResponse> {
    return this.getMyHospital().pipe(map((h) => ({ id: h.id, name: h.name }) as HospitalResponse));
  }

  /**
   * Server-side typeahead used by the super-admin hospital-scope chip
   * (docs/super-admin-cross-tenant-design.md). Backed by
   * `GET /super-admin/hospitals/search?q=&limit=`, which is gated on
   * the dedicated `isSuperAdmin` JWT claim and capped at 20 results
   * server-side.
   *
   * Caller is expected to debounce keystrokes (300 ms). An empty query is the
   * first page by name; the picker asks for it on every open, so that one
   * answer is kept for a minute and shared (a failed one is not kept).
   */
  searchHospitals(q: string, limit = 20): Observable<HospitalResponse[]> {
    if (q !== '') {
      return this.fetchHospitals(q, limit);
    }
    const cached = this.firstPages.get(limit);
    if (cached && Date.now() - cached.at <= HospitalService.FIRST_PAGE_TTL_MS) {
      return cached.page$;
    }
    const page$ = this.fetchHospitals('', limit).pipe(
      catchError((err: unknown) => {
        // Drop only this entry: a newer opener may already have replaced it.
        if (this.firstPages.get(limit)?.page$ === page$) {
          this.firstPages.delete(limit);
        }
        return throwError(() => err);
      }),
      shareReplay(1),
    );
    this.firstPages.set(limit, { at: Date.now(), page$ });
    return page$;
  }

  /** A hospital written through this service changes the first page: forget it. */
  private forgetFirstPage(): void {
    this.firstPages.clear();
  }

  private static readonly FIRST_PAGE_TTL_MS = 60_000;
  private readonly firstPages = new Map<
    number,
    { at: number; page$: Observable<HospitalResponse[]> }
  >();

  private fetchHospitals(q: string, limit: number): Observable<HospitalResponse[]> {
    const params = new HttpParams().set('q', q).set('limit', String(limit));
    return this.http.get<HospitalResponse[]>('/super-admin/hospitals/search', { params });
  }
}
