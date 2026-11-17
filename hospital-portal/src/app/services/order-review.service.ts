import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { backgroundRequestOptions } from './ws-transport';

export type OrderReviewStatus =
  'HELD' | 'CONTINUED' | 'REPLACED' | 'STOPPED' | 'ASSESSMENT_DUE' | 'ASSESSED';

export interface OrderReviewRow {
  id: string;
  disputeId: string | null;
  orderType: 'PRESCRIPTION' | 'TRANSFUSION_REQUEST' | 'RUNNING_TRANSFUSION';
  orderId: string;
  orderLabel: string | null;
  orderVersion: number;
  currentOrderVersion: number | null;
  status: OrderReviewStatus;
  controlled: boolean;
  decidedByName: string | null;
  decidedAt: string | null;
  reason: string | null;
  dosesGivenBeforeHold?: { givenAt: string; note: string | null }[];
  replacementOrderId: string | null;
  replacementCandidates: { id: string; label: string | null }[] | null;
}

export interface OrderReviewDecision {
  reason: string;
  orderVersion?: number | null;
  disputeId?: string | null;
  replacementOrderId?: string | null;
  password?: string | null;
  mfaCode?: string | null;
}

/** Orders held for a prescriber's review after a recorded death was disputed. */
@Injectable({ providedIn: 'root' })
export class OrderReviewService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/order-review-holds';

  forPatient(patientId: string): Observable<OrderReviewRow[]> {
    // QA D1524: a side panel's read; a 403 costs the panel, never the chart (background request).
    return this.http.get<OrderReviewRow[]>(
      `${this.baseUrl}/patient/${patientId}`,
      backgroundRequestOptions(),
    );
  }

  decide(
    holdId: string,
    action: 'continue' | 'replace' | 'stop' | 'assessment',
    decision: OrderReviewDecision,
  ): Observable<OrderReviewRow> {
    return this.http.post<OrderReviewRow>(`${this.baseUrl}/${holdId}/${action}`, decision);
  }
}
