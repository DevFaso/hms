import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { PriceListCategory } from './price-list.service';

/*
 * Billing-money plan PR 6 (scorecard M1): the hospital's payer catalogue
 * ("organismes payeurs") at /billing/payers — payers, their plans
 * ("formules") and the plans' rules. Bare DTOs, no wrapper. Every write
 * answers the whole payer (plans and rules included). A rule changes only by
 * adding one, or ends with a reason: there is no endpoint that edits or
 * deletes one. Writes are for the hospital admin and the accountant only
 * (owner decision D-1). Nothing here is applied to an invoice yet (PR 7).
 */

export type PayerType = 'AMU' | 'MUTUELLE' | 'ASSUREUR_PRIVE' | 'EMPLOYEUR' | 'ONG_PROGRAMME';

export const PAYER_TYPES: PayerType[] = [
  'AMU',
  'MUTUELLE',
  'ASSUREUR_PRIVE',
  'EMPLOYEUR',
  'ONG_PROGRAMME',
];

export type PayerCoverageStatus = 'SCHEDULED' | 'IN_FORCE' | 'ENDED' | 'CANCELLED';

export interface PayerPlanRule {
  id: string;
  planId: string;
  category?: PriceListCategory | null;
  priceListItemId?: string | null;
  priceListItemCode?: string | null;
  /** As the hospital typed it: never translated. */
  priceListItemLabel?: string | null;
  coveragePercent?: number | null;
  excluded: boolean;
  perActCeiling?: number | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
  status: PayerCoverageStatus;
  createdAt?: string | null;
  createdByName?: string | null;
  endedAt?: string | null;
  endedByName?: string | null;
  endReason?: string | null;
}

/** Review of #1202: one dated version of a plan's terms (insert-only). */
export interface PayerPlanTerms {
  id: string;
  defaultCoveragePercent: number;
  perInvoiceCeiling?: number | null;
  annualCeilingPerBeneficiary?: number | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
  status: PayerCoverageStatus;
  /** As the admin or accountant typed it. */
  changeReason?: string | null;
  createdAt?: string | null;
  createdByName?: string | null;
}

export interface PayerPlan {
  id: string;
  payerId: string;
  code: string;
  /** As the hospital typed it: never translated. */
  name: string;
  /** The terms in force today (the first planned ones while the plan has not started). */
  defaultCoveragePercent: number;
  perInvoiceCeiling?: number | null;
  annualCeilingPerBeneficiary?: number | null;
  /** The next planned terms after today's, if any. */
  nextTerms?: PayerPlanTerms | null;
  /** Every version of the terms, oldest start first. */
  terms?: PayerPlanTerms[];
  effectiveFrom: string;
  effectiveTo?: string | null;
  status: PayerCoverageStatus;
  /** Whether a patient's coverage may be linked to it today. */
  linkable: boolean;
  createdAt?: string | null;
  createdByName?: string | null;
  updatedAt?: string | null;
  updatedByName?: string | null;
  rules: PayerPlanRule[];
}

export interface Payer {
  id: string;
  hospitalId: string;
  code: string;
  /** As the hospital typed it: never translated. */
  name: string;
  payerType: PayerType;
  active: boolean;
  deactivatedAt?: string | null;
  deactivatedByName?: string | null;
  deactivationReason?: string | null;
  createdAt?: string | null;
  createdByName?: string | null;
  updatedAt?: string | null;
  updatedByName?: string | null;
  plans: PayerPlan[];
}

export interface PayerRequest {
  code: string;
  name: string;
  payerType: PayerType | null;
}

export interface PayerUpdateRequest {
  name: string;
  payerType: PayerType | null;
}

export interface PayerPlanRequest {
  code?: string;
  name: string;
  defaultCoveragePercent: number | null;
  perInvoiceCeiling: number | null;
  annualCeilingPerBeneficiary: number | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}

/** A plan's name and dates; its terms change only by a new dated version. */
export interface PayerPlanUpdateRequest {
  name: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}

export interface PayerPlanTermsRequest {
  defaultCoveragePercent: number | null;
  perInvoiceCeiling: number | null;
  annualCeilingPerBeneficiary: number | null;
  effectiveFrom: string | null;
  changeReason: string | null;
  replaceScheduled: boolean;
}

export interface PayerPlanRuleRequest {
  category: PriceListCategory | null;
  priceListItemId: string | null;
  coveragePercent: number | null;
  excluded: boolean;
  perActCeiling: number | null;
  effectiveFrom: string | null;
  replaceScheduled: boolean;
}

/** One plan a patient's coverage can be linked to, for the Coverage tab's "Formule" picker. */
export interface PayerPlanOption {
  id: string;
  label: string;
}

/** The plans a coverage may be linked to today, labelled "Payer — Plan", by payer then plan. */
export function linkablePlanOptions(payers: Payer[]): PayerPlanOption[] {
  const options: PayerPlanOption[] = [];
  for (const payer of payers) {
    if (!payer.active) continue;
    for (const plan of payer.plans ?? []) {
      if (plan.linkable) options.push({ id: plan.id, label: `${payer.name} — ${plan.name}` });
    }
  }
  return options;
}

@Injectable({ providedIn: 'root' })
export class PayerCatalogueService {
  private readonly http = inject(HttpClient);
  private readonly base = '/billing/payers';

  list(includeInactive: boolean): Observable<Payer[]> {
    const params = new HttpParams().set('includeInactive', String(includeInactive));
    return this.http.get<Payer[]>(this.base, { params });
  }

  get(id: string): Observable<Payer> {
    return this.http.get<Payer>(`${this.base}/${id}`);
  }

  create(body: PayerRequest): Observable<Payer> {
    return this.http.post<Payer>(this.base, body);
  }

  update(id: string, body: PayerUpdateRequest): Observable<Payer> {
    return this.http.put<Payer>(`${this.base}/${id}`, body);
  }

  /** Ends the payer with the reader's reason; its plans are no longer linked to anyone new. */
  deactivate(id: string, reason: string): Observable<Payer> {
    return this.http.post<Payer>(`${this.base}/${id}/deactivate`, { reason });
  }

  createPlan(payerId: string, body: PayerPlanRequest): Observable<Payer> {
    return this.http.post<Payer>(`${this.base}/${payerId}/plans`, body);
  }

  updatePlan(payerId: string, planId: string, body: PayerPlanUpdateRequest): Observable<Payer> {
    return this.http.put<Payer>(`${this.base}/${payerId}/plans/${planId}`, body);
  }

  /** New terms from a date (today or later): a dated version, nothing rewritten. */
  addTerms(payerId: string, planId: string, body: PayerPlanTermsRequest): Observable<Payer> {
    return this.http.post<Payer>(`${this.base}/${payerId}/plans/${planId}/terms`, body);
  }

  addRule(payerId: string, planId: string, body: PayerPlanRuleRequest): Observable<Payer> {
    return this.http.post<Payer>(`${this.base}/${payerId}/plans/${planId}/rules`, body);
  }

  /** The rule stops applying from today (a rule not started yet is withdrawn). */
  endRule(payerId: string, planId: string, ruleId: string, reason: string): Observable<Payer> {
    return this.http.post<Payer>(`${this.base}/${payerId}/plans/${planId}/rules/${ruleId}/end`, {
      reason,
    });
  }
}
