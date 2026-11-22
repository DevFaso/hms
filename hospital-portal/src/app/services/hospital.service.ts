import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, shareReplay, throwError } from 'rxjs';

/** Minimal hospital DTO returned by /me/hospital. */
export interface HospitalMinimal {
  id: string;
  name: string;
  /** QA r65 D2501: ISO 4217 code of the hospital's currency (XOF by default). */
  currencyCode?: string | null;
  /** The zone the hospital's day is counted in: its own, else its organisation's. */
  timeZone?: string | null;
}

/** QA r65 D2501: the currencies a hospital may bill in today (the CFA francs; the server decides). */
export const HOSPITAL_CURRENCIES: readonly string[] = ['XOF', 'XAF'];

/**
 * QA r65 D2501: the time zones offered for a hospital, the same list the
 * organisation form offers. The server accepts any IANA zone it knows.
 */
export const HOSPITAL_TIME_ZONES: readonly string[] = [
  'Africa/Ouagadougou',
  'Africa/Abidjan',
  'Africa/Accra',
  'Africa/Bamako',
  'Africa/Dakar',
  'Africa/Lagos',
  'Africa/Douala',
  'Africa/Nairobi',
  'Africa/Johannesburg',
  'Africa/Cairo',
  'Africa/Casablanca',
];

/** The steps of a hospital's onboarding checklist (HospitalOnboardingReadinessDTO.Step). */
export type OnboardingStep =
  | 'HOSPITAL_ADMIN'
  | 'DEPARTMENTS'
  | 'STAFF'
  | 'PROVIDERS_IN_DEPARTMENT'
  | 'SESSION_TEMPLATES'
  | 'OPEN_SLOTS'
  | 'BEDS'
  | 'PRICE_LIST'
  | 'PAYERS'
  // V356: every provisional rule set signed off by the hospital's approver (count of total).
  | 'CLINICAL_RULES';

/** QA r65 D2731: what a hospital still needs, and whether a patient can book online yet. */
export interface HospitalOnboardingReadiness {
  hospitalId: string;
  patientBookable: boolean;
  items: {
    step: OnboardingStep;
    done: boolean;
    count: number;
    requiredForBooking: boolean;
    /** V356: the count the step is done at (CLINICAL_RULES: n of N); absent for the steps done at one. */
    total?: number | null;
  }[];
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
  /** QA r65 D2500: the super-admin's own code; blank on create generates one, blank on edit keeps it. */
  code?: string;
  /** QA r65 D2501: ISO 4217 code; blank keeps XOF (create) or the stored one (edit). */
  currencyCode?: string;
  /** QA r65 D2501: IANA zone id; '' means "the organisation's default time zone". */
  timeZone?: string;
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
  lifecycleState?: 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED' | 'PENDING_PURGE' | 'PURGED';
  createdAt: string;
  updatedAt: string;
  /** QA r65 D2501: ISO 4217 code of the currency the hospital bills in. */
  currencyCode?: string | null;
  /** The hospital's own IANA zone; null when it follows its organisation. */
  timeZone?: string | null;
  /** The zone its day is counted in: its own, else its organisation's default. */
  effectiveTimeZone?: string | null;
  /** QA D3059: on GET /hospitals/{id} only. The code is fixed once an invoice number exists. */
  codeLocked?: boolean | null;
  /** QA D3059: on GET /hospitals/{id} only. The currency is fixed once an invoice or a price exists. */
  currencyLocked?: boolean | null;
}

/**
 * QA D818: the lifecycle states that take no new role assignment, mirroring
 * the server's FacilityAssignmentGuard.CLOSED_STATES (which answers 409).
 */
const CLOSED_FOR_ASSIGNMENT: readonly string[] = ['ARCHIVED', 'PENDING_PURGE', 'PURGED'];

/**
 * QA D818: whether a hospital may be offered in a picker that grants a role
 * there. An absent state (an older payload) is treated as open; the server
 * still decides.
 */
export function isOpenForAssignment(hospital: { lifecycleState?: string | null }): boolean {
  return !CLOSED_FOR_ASSIGNMENT.includes(hospital.lifecycleState ?? '');
}

/**
 * Platform admin rules 2026-11-20, rule 1a: one hospital of the caller's own
 * organisation (`GET /organization-admin/hospitals`), for an organisation
 * admin's pickers.
 */
export interface OrganisationHospital {
  id: string;
  name: string;
  city?: string | null;
  code?: string | null;
  currencyCode?: string | null;
  lifecycleState?: string | null;
}

/** A count in a sensitive category: withheld (no value at all) when it is small. */
export interface SensitiveCount {
  value: number | null;
  suppressed: boolean;
}

/** Rule 1b: one hospital's totals for a period. Totals only: nothing about a person. */
export interface OrganisationHospitalTotals {
  hospitalId: string;
  hospitalName: string;
  currencyCode: string;
  activeStaff: number;
  appointments: number;
  admissions: number;
  invoicedTotal: number;
  collectedTotal: number;
  deaths: SensitiveCount;
}

export interface OrganisationSummary {
  from: string;
  to: string;
  smallCountThreshold: number;
  hospitals: OrganisationHospitalTotals[];
}

/** One row of `GET /hospitals/directory`: no contact details. */
export interface HospitalDirectoryEntry {
  id: string;
  name: string;
  city: string | null;
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

  /**
   * QA D36: every active hospital as id, name and city, for the destination
   * picker of cross-hospital workflows. `list()` returns only the caller's
   * own hospitals unless they are a super-admin.
   */
  directory(): Observable<HospitalDirectoryEntry[]> {
    return this.http.get<HospitalDirectoryEntry[]>('/hospitals/directory');
  }

  getById(id: string): Observable<HospitalResponse> {
    return this.http.get<HospitalResponse>(`/hospitals/${id}`);
  }

  /**
   * QA r65 D2731: the onboarding checklist of a hospital. The hospital admin
   * of that hospital and the super-admin only (the server refuses anyone else).
   */
  onboardingReadiness(hospitalId: string): Observable<HospitalOnboardingReadiness> {
    return this.http.get<HospitalOnboardingReadiness>(
      `/hospitals/${hospitalId}/onboarding-readiness`,
    );
  }

  /**
   * Platform admin rules 2026-11-20, rule 1a: the hospitals of the caller's
   * own organisation. The organisation admin only; the request names no
   * hospital and no organisation, the server reads both from the caller.
   */
  organisationHospitals(): Observable<OrganisationHospital[]> {
    return this.http.get<OrganisationHospital[]>('/organization-admin/hospitals');
  }

  /** Rule 1b: totals per hospital of the caller's organisation, for the current month to date. */
  organisationSummary(): Observable<OrganisationSummary> {
    return this.http.get<OrganisationSummary>('/organization-admin/summary');
  }

  create(req: HospitalRequest): Observable<HospitalResponse> {
    return this.http.post<HospitalResponse>('/hospitals', req);
  }

  update(id: string, req: HospitalRequest): Observable<HospitalResponse> {
    return this.http.put<HospitalResponse>(`/hospitals/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/hospitals/${id}`);
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
    const now = Date.now();
    const cached = this.firstPage;
    if (cached && cached.limit === limit && now - cached.at <= HospitalService.FIRST_PAGE_TTL_MS) {
      return cached.page$;
    }
    const page$ = this.fetchHospitals('', limit).pipe(
      catchError((err: unknown) => {
        // Drop only this entry: a newer opener may already have replaced it.
        if (this.firstPage?.page$ === page$) {
          this.firstPage = undefined;
        }
        return throwError(() => err);
      }),
      shareReplay(1),
    );
    this.firstPage = { at: now, limit, page$ };
    return page$;
  }

  /**
   * Forget the cached first page. Called by `hospitalCacheInterceptor` after
   * any write under /hospitals or /organizations (create, update, delete,
   * suspend, restore, archive…), whichever service issued it.
   */
  forgetFirstPage(): void {
    this.firstPage = undefined;
  }

  private static readonly FIRST_PAGE_TTL_MS = 60_000;
  private firstPage?: { at: number; limit: number; page$: Observable<HospitalResponse[]> };

  private fetchHospitals(q: string, limit: number): Observable<HospitalResponse[]> {
    const params = new HttpParams().set('q', q).set('limit', String(limit));
    return this.http.get<HospitalResponse[]>('/super-admin/hospitals/search', { params });
  }
}
