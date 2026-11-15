import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { backgroundRequestOptions } from './ws-transport';

/** r53 (provisional rule 2026-11-14): one safeguarding review, for the treating team and the hospital's administrators. */
export interface SafeguardingReview {
  id: string;
  patientId: string;
  patientName?: string | null;
  triggerCode: string;
  sourceType: string;
  patientAgeYears: number;
  ageThresholdYears: number;
  provisionalRule: boolean;
  status: 'OPEN' | 'REVIEWED';
  raisedAt: string;
  raisedByName?: string | null;
  reviewedAt?: string | null;
  reviewedByName?: string | null;
  reviewNote?: string | null;
}

/** The only caller of /safeguarding (SafeguardingAccessGuardTest). */
@Injectable({ providedIn: 'root' })
export class SafeguardingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/safeguarding';

  /** The patient's reviews at the acting hospital; a background read that never redirects the chart. */
  forPatient(patientId: string): Observable<SafeguardingReview[]> {
    return this.http.get<SafeguardingReview[]>(
      `${this.baseUrl}/patients/${patientId}/reviews`,
      backgroundRequestOptions(),
    );
  }

  recordReview(reviewId: string, note: string): Observable<SafeguardingReview> {
    return this.http.post<SafeguardingReview>(`${this.baseUrl}/reviews/${reviewId}/review`, {
      note,
    });
  }
}
