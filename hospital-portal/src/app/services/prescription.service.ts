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

  list(filters?: {
    patientId?: string;
    staffId?: string;
    hospitalId?: string;
  }): Observable<PrescriptionResponse[]> {
    // Most-recently-touched first. The endpoint takes a Pageable and applied
    // no sort at all, so the default page of 20 was an arbitrary slice of the
    // hospital's prescriptions: a prescriber could be told by the clinical
    // inbox that N orders await clarification and never find one of them on
    // this page. Any write — signing, a pharmacist's question, the answer —
    // bumps `updatedAt`, so the rows that need attention are on page one.
    let params = new HttpParams().set('sort', 'updatedAt,desc');
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
