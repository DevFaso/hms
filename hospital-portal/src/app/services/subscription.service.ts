import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  OrganizationSubscription,
  OrganizationSubscriptionRequest,
  SubscriptionPlan,
  SubscriptionPlanRequest,
} from './subscription.model';

const BASE = '/api/super-admin/subscriptions';

/**
 * MVP-6: Plan + per-org subscription endpoints. All require ROLE_SUPER_ADMIN.
 */
@Injectable({ providedIn: 'root' })
export class SubscriptionService {
  private readonly http = inject(HttpClient);

  listPlans(activeOnly = true): Observable<SubscriptionPlan[]> {
    const params = new HttpParams().set('activeOnly', String(activeOnly));
    return this.http.get<SubscriptionPlan[]>(`${BASE}/plans`, { params });
  }

  createPlan(body: SubscriptionPlanRequest): Observable<SubscriptionPlan> {
    return this.http.post<SubscriptionPlan>(`${BASE}/plans`, body);
  }

  updatePlan(planId: string, body: SubscriptionPlanRequest): Observable<SubscriptionPlan> {
    return this.http.put<SubscriptionPlan>(`${BASE}/plans/${planId}`, body);
  }

  deactivatePlan(planId: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/plans/${planId}`);
  }

  listForOrganization(organizationId: string): Observable<OrganizationSubscription[]> {
    return this.http.get<OrganizationSubscription[]>(`${BASE}/organizations/${organizationId}`);
  }

  getActiveForOrganization(organizationId: string): Observable<OrganizationSubscription | null> {
    return this.http.get<OrganizationSubscription | null>(
      `${BASE}/organizations/${organizationId}/active`,
    );
  }

  assignPlan(
    organizationId: string,
    body: OrganizationSubscriptionRequest,
  ): Observable<OrganizationSubscription> {
    return this.http.post<OrganizationSubscription>(
      `${BASE}/organizations/${organizationId}`,
      body,
    );
  }

  cancel(organizationId: string, subscriptionId: string): Observable<OrganizationSubscription> {
    return this.http.delete<OrganizationSubscription>(
      `${BASE}/organizations/${organizationId}/${subscriptionId}`,
    );
  }
}
