import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { STEP_UP_REQUEST } from './step-up';

/**
 * Round 18: the prescriber's re-authentication when signing a controlled-drug
 * prescription: the TOTP code when an authenticator is enrolled, else the
 * password. Checked on that one call.
 */
export interface SignReauthentication {
  password?: string;
  mfaCode?: string;
}

/**
 * POST /prescriptions/{id}/sign (SignPrescriptionRequestDTO): the
 * re-authentication of a controlled drug, and (QA D1183, round 23) the
 * prescriber's reason for signing an order the allergy stop applies to when
 * no acknowledgement was recorded at creation or edit (a documented allergy
 * covers the drug, or the drug name could not be checked against a drug-class
 * allergy: `prescription.allergy.unverifiable`).
 */
export interface SignPrescriptionBody extends SignReauthentication {
  allergyOverrideReason?: string;
  /** CR-02: the token the refused signature answered with (`CdsCard.exceptionKey`). */
  doseExceptionToken?: string;
}
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CdsCard } from '../shared/cds-card/cds-card.model';
import { ALL_PAGES_SIZE, PageSlice, allRows } from '../shared/all-pages';

export interface PrescriptionResponse {
  id: string;
  patientId: string;
  patientFullName: string;
  patientEmail: string;
  /** QA D912: the patient's primary phone; absent from a server that predates it. */
  patientPhone?: string;
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
  /** QA round 30: the minimum hours between two doses of an as-needed order, or null. */
  prnMinIntervalHours?: number | null;
  /** QA D1378 (round 32): the order is as-needed (PRN), as the prescriber ticked it. */
  asNeeded?: boolean;
  /** QA D1452: the MAR cannot schedule this wording: nurses give a reason at each later dose. */
  frequencyUnread?: boolean;
  /** Administration route code (PO, IV, ...); null on orders that never recorded one. */
  route?: string | null;
  duration: string;
  notes: string;
  /**
   * What was ordered (gap G13), so a remainder can be rendered with its unit
   * instead of as a bare number. Optional: the columns are nullable and
   * pre-date the pharmacy module, so a legacy row carries neither.
   */
  quantity?: number | null;
  quantityUnit?: string | null;
  /**
   * How many refills the prescriber granted, how many are left, and how many
   * have actually been released back to the pharmacy. `refillsUsed` is the
   * one the remainder arithmetic needs: the expected LIFETIME quantity is
   * `quantity * (1 + refillsUsed)`, which is what `DispenseServiceImpl`
   * compares the sum of the fills against.
   */
  refillsAllowed?: number | null;
  refillsRemaining?: number | null;
  refillsUsed?: number | null;
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
  /** QA D934: a nurse's or midwife's unsigned draft, which a doctor of the hospital signs. */
  awaitingPhysicianSignature?: boolean;
  /** QA D922: the catalogue item the order was written from, or resolved to. */
  medicationCatalogItemId?: string | null;
  /** QA D757: a take-home order outlives its stay. */
  takeHome?: boolean;
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

  /* ── Overrides and the paediatric dose review (round 19) ─────────── */

  /**
   * QA D1101: why the prescriber passed an alert - a documented allergy
   * (`allergyOverride` true) or a critical decision-support alert such as a
   * paediatric dose soft stop. Absent when nothing was overridden, and from
   * the patient's copy.
   */
  overrideReason?: string | null;
  allergyOverride?: boolean | null;
  /** QA D1096: the paediatric dose check asked for a recorded review before signing. */
  pediatricDoseReviewRequired?: boolean | null;
  pediatricDoseReviewedAt?: string | null;
  pediatricDoseReviewedByName?: string | null;
  pediatricDoseReviewReason?: string | null;
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
  /**
   * QA round 30 (user decision 2026-11-02): the minimum hours between two
   * doses of an as-needed (PRN, "si besoin") order; required on one, refused
   * on any other (fieldErrors.prnMinIntervalHours).
   */
  prnMinIntervalHours?: number | null;
  /**
   * QA D1378 (round 32): the prescriber's explicit "as needed (PRN)" tick. A
   * condition in the frequency sent without it is refused (fieldErrors.asNeeded).
   */
  asNeeded?: boolean;
  /**
   * QA D1419: the prescriber's answer to "this wording cannot be scheduled"
   * (fieldErrors.frequencyConfirmed): the order is created and the MAR flags it.
   */
  frequencyConfirmed?: boolean;
  /** D109: the route the MAR five-rights check compares the bedside entry with. */
  route?: string;
  duration?: string;
  /** QA D706: the quantity to hand over per fill; the dispense is capped at it. */
  quantity?: number | null;
  quantityUnit?: string;
  notes?: string;
  status?: PrescriptionStatusType;
  forceOverride?: boolean;
  /** Required with forceOverride when a documented allergy matches (QA D83). */
  overrideReason?: string;
  /**
   * Safeguard flags (P2 #15). Set-only: the backend refuses clearing a flag by
   * edit. controlledSubstance is deliberately not offered by the form yet —
   * the two-factor transport is an open decision, so flagging it would make
   * the prescription unsignable; requiresCosign is fully usable.
   */
  controlledSubstance?: boolean;
  requiresCosign?: boolean;
  /**
   * QA D922/D935: the catalogue item picked in the form, so the catalogue's
   * controlled flag and paediatric ceiling apply. The server also resolves a
   * typed name to the hospital's item when this is absent.
   */
  medicationCatalogItemId?: string | null;
  /** QA D757: the order goes home with the patient; every other stay order ends at discharge. */
  takeHome?: boolean;
  /** QA D660: mandatory when a SIGNED prescription is cancelled or discontinued. */
  withdrawalReason?: string;
}

@Injectable({ providedIn: 'root' })
export class PrescriptionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/prescriptions';

  /**
   * The transfer size of one page of the prescriber's list.
   *
   * <p>`GET /prescriptions` declares no `@PageableDefault`, so without an
   * explicit size and sort it served Spring's default page 0 of 20 from an
   * UNORDERED derived query. QA D1347 (round 30): the list then read ONE page
   * of 200 and the tabs counted that page, so on a busy tenant "Needs
   * attention" could miss an order the clinical inbox pointed at. `list` now
   * reads EVERY page; this is only how many rows each request carries.
   */
  static readonly LIST_PAGE_SIZE = ALL_PAGES_SIZE;

  /**
   * Every prescription the filters select, newest first. Reads every page
   * (`allRows`); past the helper's page ceiling the stream FAILS rather than
   * returning a partial list, and the caller says the load failed.
   */
  list(filters?: {
    patientId?: string;
    staffId?: string;
    hospitalId?: string;
    /** One visit's prescriptions (used without patientId, which the server reads first). */
    encounterId?: string;
    /**
     * Gap G12 — restrict the list to these `PrescriptionStatus` values. One
     * repeated `status` query parameter per value. Omitted or empty means
     * every status, which is what the endpoint did before the filter existed.
     */
    statuses?: string[];
  }): Observable<PrescriptionResponse[]> {
    // An explicit sort, with `id` as the tiebreak: rows sharing a `createdAt`
    // would otherwise have no stable order between two page reads, and a row
    // could be served on both pages or on neither. Sorting by updatedAt was
    // tried and dropped — every sign, edit and fill bumps that column, so the
    // rows would move between the pages of one read.
    let filterParams = new HttpParams();
    if (filters) {
      if (filters.patientId) filterParams = filterParams.set('patientId', filters.patientId);
      if (filters.staffId) filterParams = filterParams.set('staffId', filters.staffId);
      if (filters.hospitalId) filterParams = filterParams.set('hospitalId', filters.hospitalId);
      if (filters.encounterId) filterParams = filterParams.set('encounterId', filters.encounterId);
      for (const status of filters.statuses ?? []) {
        filterParams = filterParams.append('status', status);
      }
    }
    return allRows<PrescriptionResponse>(
      (page, size) =>
        this.http.get<PageSlice<PrescriptionResponse>>(this.baseUrl, {
          params: filterParams
            .set('page', page)
            .set('size', size)
            .append('sort', 'createdAt,desc')
            .append('sort', 'id,desc'),
        }),
      PrescriptionService.LIST_PAGE_SIZE,
    );
  }

  /**
   * QA D1412: one page of the list and the server's own total. Same order as
   * {@link list}. `/prescriptions` shows these; every row is one page away.
   * QA D1314: `search` narrows it on the server (drug, patient or prescriber
   * name, accents ignored); the total is then the count of the matches.
   */
  page(
    filters: { statuses?: readonly string[]; search?: string },
    page: number,
    size: number,
  ): Observable<PageSlice<PrescriptionResponse>> {
    let params = new HttpParams();
    for (const status of filters.statuses ?? []) {
      params = params.append('status', status);
    }
    const search = filters.search?.trim();
    if (search) {
      params = params.set('search', search);
    }
    return this.http.get<PageSlice<PrescriptionResponse>>(this.baseUrl, {
      params: params
        .set('page', page)
        .set('size', size)
        .append('sort', 'createdAt,desc')
        .append('sort', 'id,desc'),
    });
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
  sign(id: string, body?: SignPrescriptionBody): Observable<PrescriptionResponse> {
    // Round 18: a controlled drug is signed with the prescriber's
    // re-authentication in the body (never a header, never stored). The call
    // is marked as a step-up, so a refused password or code comes back to the
    // sign dialog instead of signing the user out or opening the 403 page.
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/sign`, body ?? {}, {
      context: new HttpContext().set(STEP_UP_REQUEST, true),
    });
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
  /**
   * QA D1096: a pharmacist or a physician other than the prescriber records
   * the paediatric dose review the decision support asked for. The reason is
   * mandatory; the order is not signed until the review is recorded.
   */
  recordPediatricDoseReview(id: string, reason: string): Observable<PrescriptionResponse> {
    return this.http.post<PrescriptionResponse>(`${this.baseUrl}/${id}/pediatric-dose-review`, {
      reason: reason.trim(),
    });
  }

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
