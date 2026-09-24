import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CdsCard } from '../shared/cds-card/cds-card.model';

export interface PrescriptionResponse {
  id: string;
  patientId: string;
  patientFullName: string;
  patientEmail: string;
  staffId: string;
  staffFullName: string;
  encounterId: string;
  hospitalId: string;
  /**
   * Display name for the prescription's hospital, populated by the
   * backend mapper. Used by the super-admin cross-tenant list view
   * (docs/super-admin-cross-tenant-design.md). Optional for backwards
   * compatibility with older snapshots that may lack the field.
   */
  hospitalName?: string;
  medicationName: string;
  medicationDisplayName: string;
  dosage: string;
  frequency: string;
  duration: string;
  notes: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  /**
   * CDS rule-engine cards returned by the backend on create / update.
   * Optional because read-only responses (list, get-by-id) do not
   * re-run the rule engine.
   */
  cdsAdvisories?: CdsCard[];
  /** Safeguard state (P2 #15) — why a sign/dispense was refused, made visible. */
  controlledSubstance?: boolean;
  requiresCosign?: boolean;
  twoFactorVerifiedAt?: string | null;
  cosignedAt?: string | null;
  cosignedByStaffId?: string | null;
  /** Signature evidence (P2 #16). Null on a SIGNED row = signed before V118. */
  signatureValue?: string | null;
  signatureAlgorithm?: string | null;
  signedAt?: string | null;
  signedByStaffId?: string | null;
  /**
   * Pharmacist verification (Tier 2 item 33). In scope only for controlled
   * substances and prescriptions flagged requiresCosign; everything else
   * reports false and administers as before.
   */
  requiresPharmacistVerification?: boolean;
  /**
   * Null means unverified — either never verified, or invalidated because the
   * prescription was edited after verification. The two are deliberately
   * indistinguishable here: both mean a pharmacist has not checked the drug
   * and dose currently on the row.
   */
  pharmacistVerifiedAt?: string | null;
  pharmacistVerifiedByUserId?: string | null;
  pharmacistVerifiedByName?: string | null;
  pharmacistVerificationNote?: string | null;

  /* ── Pharmacy + dispatch state (gap G7, wave 1) ──────────────────── */

  /**
   * Where the prescription went: the partner pharmacy it was routed to, or the
   * community pharmacy it was dispatched to by SMS. Null for an order still at
   * the hospital's own dispensary — and also for one a partner REFUSED, because
   * `StockOutRoutingServiceImpl` clears these three columns on a rejection and
   * on a no-show. A PARTNER_REJECTED row therefore says nothing here about who
   * refused it; only the routing history does.
   */
  pharmacyId?: string | null;
  pharmacyName?: string | null;
  pharmacyContact?: string | null;

  /** `SMS` — the only channel PrescriptionSmsDispatchServiceImpl writes. */
  dispatchChannel?: string | null;
  /** `SENT` on the prescription row; a failure stays on the transmission. */
  dispatchStatus?: string | null;
  dispatchedAt?: string | null;

  /**
   * The current status while the pharmacy owns it (the backend mapper's
   * PHARMACY_OWNED_STATUSES), null while the order is still the prescriber's.
   * It is a PrescriptionStatus name — render it through
   * `| enumLabel: 'prescriptionStatus'`, never raw.
   */
  lastPharmacyEvent?: string | null;
  lastPharmacyEventAt?: string | null;

  /* ── Pharmacist clarification (gap G5) ───────────────────────── */

  /**
   * Pharmacist clarification exchange (gap G5). The backend strips all four
   * from the patient's copy of a prescription, so they are optional here and
   * absent rather than blank when the reader is a patient.
   */
  clarificationReason?: string | null;
  clarificationRequestedAt?: string | null;
  clarificationResponse?: string | null;
  clarificationResolvedAt?: string | null;
}

/**
 * Every value `com.example.hms.enums.PrescriptionStatus` can send, in its
 * declaration order.
 *
 * <p>This is a HAND-MAINTAINED copy — nothing in the portal build reads the
 * Java enum — so a status added to the backend does not on its own fail a spec
 * here: the exhaustiveness spec iterates this list, which would not yet know
 * about it. Two things do catch it. `npm run i18n:enums` reads
 * `PrescriptionStatus.java` and fails on a constant with no
 * `PORTAL.ENUM.PRESCRIPTION_STATUS` key, and `tabForStatus` files an unmapped
 * value under "Needs attention" rather than out of every tab. Keying the new
 * status is what brings it here; the spec then holds the partition.
 */
export const PRESCRIPTION_STATUSES: readonly string[] = [
  'DRAFT',
  'PENDING_SIGNATURE',
  'SIGNED',
  'TRANSMITTED',
  'TRANSMISSION_FAILED',
  'CANCELLED',
  'DISCONTINUED',
  'PENDING_CLARIFICATION',
  'DISPENSED',
  'PARTIALLY_FILLED',
  'PENDING_STOCK',
  'REQUIRES_EXTERNAL_FILL',
  'SENT_TO_PARTNER',
  'PARTNER_ACCEPTED',
  'PARTNER_REJECTED',
  'PARTNER_DISPENSED',
  'PRINTED_FOR_PATIENT',
];

export type PrescriptionStatusType =
  | 'DRAFT'
  | 'PENDING_SIGNATURE'
  | 'SIGNED'
  | 'TRANSMITTED'
  | 'TRANSMISSION_FAILED'
  | 'CANCELLED'
  | 'DISCONTINUED';

export interface PrescriptionRequest {
  patientId?: string;
  patientIdentifier?: string;
  staffId?: string;
  encounterId?: string;
  medicationName: string;
  dosage?: string;
  frequency?: string;
  duration?: string;
  notes?: string;
  status?: PrescriptionStatusType;
  forceOverride?: boolean;
  /**
   * Safeguard flags (P2 #15). Set-only: the backend refuses clearing a flag by
   * edit. controlledSubstance is deliberately not offered by the form yet —
   * the two-factor transport is an open decision, so flagging it would make
   * the prescription unsignable; requiresCosign is fully usable.
   */
  controlledSubstance?: boolean;
  requiresCosign?: boolean;
}

@Injectable({ providedIn: 'root' })
export class PrescriptionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/prescriptions';

  /**
   * How many rows the prescriber's list asks for.
   *
   * <p>`GET /prescriptions` declares no `@PageableDefault`, so it was serving
   * Spring's default page 0 of 20 from an UNORDERED derived query — an
   * arbitrary twenty rows out of the tenant. That was survivable while the
   * page only listed what it had; it stopped being survivable when the tabs
   * started counting, because a "Needs attention 0" is a confident claim that
   * nothing is waiting. The page still has a ceiling, and the UI says so when
   * it is reached rather than pretending otherwise.
   */
  static readonly LIST_PAGE_SIZE = 200;

  list(filters?: {
    patientId?: string;
    staffId?: string;
    hospitalId?: string;
  }): Observable<PrescriptionResponse[]> {
    // An explicit size and sort. Without them the derived query's order is
    // arbitrary, so "the first page" is not even the newest prescriptions:
    // a prescriber told by the clinical inbox that N orders await
    // clarification could find none of them here. Sorting by updatedAt was
    // tried and dropped — it reorders the page for everyone and still loses
    // the row on a busy day, because every sign, edit and fill bumps that
    // column. This makes the page deterministic and large enough to count
    // from; the real fix is a status filter on GET /prescriptions, which is
    // filed as backend work.
    let params = new HttpParams()
      .set('size', PrescriptionService.LIST_PAGE_SIZE)
      .set('sort', 'createdAt,desc');
    if (filters) {
      if (filters.patientId) params = params.set('patientId', filters.patientId);
      if (filters.staffId) params = params.set('staffId', filters.staffId);
      if (filters.hospitalId) params = params.set('hospitalId', filters.hospitalId);
    }
    return this.http
      .get<{ content: PrescriptionResponse[] }>(this.baseUrl, { params })
      .pipe(map((res) => res?.content ?? []));
  }

  getById(id: string): Observable<PrescriptionResponse> {
    return this.http.get<PrescriptionResponse>(`${this.baseUrl}/${id}`);
  }

  create(req: PrescriptionRequest): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(this.baseUrl, req);
  }

  update(id: string, req: PrescriptionRequest): Observable<PrescriptionResponse> {
    return this.http.put<PrescriptionResponse>(`${this.baseUrl}/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /**
   * The signing ceremony. `SIGNED` is deliberately not settable through
   * `update()` — the backend rejects a client-asserted signature — because this
   * is the only path that records who signed, when, and a digest of what they
   * signed. Only the prescribing clinician can call it.
   */
  sign(id: string): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/sign`, {});
  }

  /**
   * Co-sign ceremony (P2 #15): a second prescriber puts their judgment on
   * record. The backend refuses the prescription's own prescriber.
   */
  cosign(id: string): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/cosign`, {});
  }

  /**
   * Pharmacist-verification ceremony (Tier 2 item 33): the check between
   * prescriber and nurse. Pharmacist roles only, and the backend refuses the
   * prescribing clinician even if they hold one.
   *
   * <p>The note is optional by design — the common case is a clean check with
   * nothing to add, and a mandatory field there trains people to type "ok".
   */
  pharmacistVerify(id: string, note?: string): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/pharmacist-verify`, {
      note: note?.trim() || undefined,
    });
  }

  /**
   * Gap G5, pharmacy side: the pharmacist sends the order back to its
   * prescriber with a question. The backend moves it to
   * PENDING_CLARIFICATION, which takes it off the dispense work queue, so
   * the caller must make that consequence explicit before calling.
   *
   * <p>Pharmacist roles only (PHARMACIST, PHARMACY_VERIFIER, SUPER_ADMIN).
   * The reason is required and capped at 1000 characters server-side.
   */
  requestClarification(id: string, reason: string): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/request-clarification`, {
      reason: reason.trim(),
    });
  }

  /**
   * Gap G5, prescriber side: a doctor with a staff profile at the
   * prescribing hospital answers, and the order returns to the status it
   * held when the question was asked.
   *
   * <p>The answer is optional — the doctor may have edited the order instead
   * of, or as well as, replying — so an empty box sends no `response` at all
   * rather than an empty string.
   */
  resolveClarification(id: string, response?: string): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/resolve-clarification`, {
      response: response?.trim() || undefined,
    });
  }

  dispatchSms(
    id: string,
    pharmacyId: string,
    note?: string,
  ): Observable<PrescriptionSmsDispatchResult> {
    return this.http
      .post<{
        data: PrescriptionSmsDispatchResult;
      }>(`${this.baseUrl}/${id}/dispatch-sms`, { pharmacyId, note: note ?? null })
      .pipe(map((r) => r.data));
  }
}

export interface PrescriptionSmsDispatchResult {
  prescriptionId: string;
  transmissionId: string;
  pharmacyId: string;
  pharmacyName: string;
  destinationPhone: string;
  status: string;
  dispatchedAt: string;
}

export interface CommunityPharmacyOption {
  id: string;
  name: string;
  phoneNumber: string;
  pharmacyType: string;
}

@Injectable({ providedIn: 'root' })
export class CommunityPharmacyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/pharmacies/community';

  list(hospitalId?: string): Observable<CommunityPharmacyOption[]> {
    let params = new HttpParams();
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    return this.http.get<CommunityPharmacyOption[]>(this.baseUrl, { params });
  }
}
