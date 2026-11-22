import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiResponse } from './pharmacy.service';

/*
 * Policy rule 6 (provisional, V345): « à facturer » is an exception somebody else approves. Somebody asks, another
 * person approves or rejects, the pharmacist executes at the hand-over; an urgency override is reviewed afterwards.
 * Policy rules 1 and 6: the hospital's own billing policy.
 */

/** What is asked for (the first two) and what the counter records by itself (the last two). */
export type BillingDeferralKind =
  'THIRD_PARTY_COVER' | 'OUTPATIENT_CREDIT' | 'URGENCY_OVERRIDE' | 'ADMISSION_ACCOUNT';

export type BillingDeferralStatus =
  'REQUESTED' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | 'CANCELLED';

export interface BillingDeferral {
  id: string;
  prescriptionId: string;
  patientId: string;
  patientName?: string | null;
  /** The patient's MRN at this hospital. */
  patientMrn?: string | null;
  medicationName?: string | null;
  dispenseId?: string | null;
  kind: BillingDeferralKind;
  status: BillingDeferralStatus;
  reason?: string | null;
  requestedByName?: string | null;
  requestedAt: string;
  decidedByName?: string | null;
  decidedAt?: string | null;
  decisionNote?: string | null;
  creditLimit?: number | null;
  coverReference?: string | null;
  executedByName?: string | null;
  executedAt?: string | null;
  amountDue?: number | null;
  /** An executed deferral only: what its fills still owe now (paid, covered or cancelled since: nothing). */
  stillDue?: number | null;
  /** QA D3406 (r70): an open deferral: the prepared fill's charge and what it still owes. */
  fillCharge?: number | null;
  fillDue?: number | null;
  /** QA D3408 (r70): a third-party cover: the insurer share by the patient's coverage, and the patient share. */
  insurerShare?: number | null;
  patientShare?: number | null;
  reviewedByName?: string | null;
  reviewedAt?: string | null;
  reviewNote?: string | null;
  /** The reader may approve or reject it: an approver of its kind who did not ask for it. */
  canDecide: boolean;
  /** The reader may review this urgency override: a reviewer who did not make it. */
  canReview: boolean;
  /** The reader may hand a fill over on it: approved, and not approved by them. */
  canExecute: boolean;
}

export interface BillingDeferralRequest {
  prescriptionId: string;
  kind: 'THIRD_PARTY_COVER' | 'OUTPATIENT_CREDIT';
  reason: string;
  coverReference?: string;
}

export interface BillingDeferralDecision {
  note?: string;
  coverReference?: string;
  creditLimit?: number;
}

/** What the counter sends with a fill that has money due: an approved deferral, or the reason of an override. */
export interface BillingDeferralChoice {
  billingDeferralId?: string;
  billingUrgencyReason?: string;
}

/** What the counter reads before a fill of a prescription leaves with money due. */
export interface BillingDeferralCounter {
  /** An inpatient item: what is due goes on the account of the open stay, nobody approves. */
  onOpenStay: boolean;
  /** The deferrals of the prescription asked for, or approved by somebody else. */
  open: BillingDeferral[];
}

/** Policy rules 1 and 6: the hospital's billing and dispensing choices. */
export interface HospitalBillingPolicy {
  autoConsultationOnPlannedAdmission: boolean;
  /** Always off: nothing is built behind it and the server refuses to turn it on. */
  pharmacistExceptionalRenewal: boolean;
  updatedAt?: string | null;
  canEdit: boolean;
}

@Injectable({ providedIn: 'root' })
export class BillingDeferralService {
  private readonly http = inject(HttpClient);

  /** The work list by default (open deferrals and overrides to review); or by status; or of one prescription. */
  list(
    statuses: BillingDeferralStatus[] = [],
    prescriptionId?: string,
    stillDue = false,
  ): Observable<ApiResponse<BillingDeferral[]>> {
    let params = new HttpParams();
    for (const s of statuses) params = params.append('status', s);
    if (prescriptionId) params = params.set('prescriptionId', prescriptionId);
    // « Sorties, encore dues »: only the executed deferrals whose fills still owe money now.
    if (stillDue) params = params.set('stillDue', 'true');
    return this.http.get<ApiResponse<BillingDeferral[]>>('/pharmacy/billing-deferrals', { params });
  }

  counter(prescriptionId: string): Observable<ApiResponse<BillingDeferralCounter>> {
    return this.http.get<ApiResponse<BillingDeferralCounter>>(
      `/pharmacy/billing-deferrals/counter/${prescriptionId}`,
    );
  }

  request(body: BillingDeferralRequest): Observable<ApiResponse<BillingDeferral>> {
    return this.http.post<ApiResponse<BillingDeferral>>('/pharmacy/billing-deferrals', body);
  }

  approve(id: string, body: BillingDeferralDecision): Observable<ApiResponse<BillingDeferral>> {
    return this.http.post<ApiResponse<BillingDeferral>>(
      `/pharmacy/billing-deferrals/${id}/approve`,
      body,
    );
  }

  reject(id: string, reason: string): Observable<ApiResponse<BillingDeferral>> {
    return this.http.post<ApiResponse<BillingDeferral>>(
      `/pharmacy/billing-deferrals/${id}/reject`,
      { reason },
    );
  }

  cancel(id: string, reason: string): Observable<ApiResponse<BillingDeferral>> {
    return this.http.post<ApiResponse<BillingDeferral>>(
      `/pharmacy/billing-deferrals/${id}/cancel`,
      { reason },
    );
  }

  review(id: string, reason: string): Observable<ApiResponse<BillingDeferral>> {
    return this.http.post<ApiResponse<BillingDeferral>>(
      `/pharmacy/billing-deferrals/${id}/review`,
      { reason },
    );
  }

  policy(): Observable<ApiResponse<HospitalBillingPolicy>> {
    return this.http.get<ApiResponse<HospitalBillingPolicy>>('/billing/policy');
  }

  updatePolicy(
    body: Partial<Pick<HospitalBillingPolicy, 'autoConsultationOnPlannedAdmission'>>,
  ): Observable<ApiResponse<HospitalBillingPolicy>> {
    return this.http.put<ApiResponse<HospitalBillingPolicy>>('/billing/policy', body);
  }
}
