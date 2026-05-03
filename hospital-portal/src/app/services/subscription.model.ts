/**
 * MVP-6: Subscription plan + organization-subscription DTOs.
 */
export interface SubscriptionPlan {
  id: string;
  name: string;
  tierCode: string;
  description: string | null;
  monthlyPriceCents: number;
  currency: string;
  includedSeats: number;
  featureKeys: string;
  active: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface SubscriptionPlanRequest {
  name: string;
  tierCode: string;
  description?: string;
  monthlyPriceCents: number;
  currency?: string;
  includedSeats: number;
  featureKeys?: string;
  active?: boolean;
}

export interface OrganizationSubscription {
  id: string;
  organizationId: string;
  organizationName: string | null;
  planId: string;
  planName: string | null;
  planTierCode: string | null;
  seatLimit: number;
  billingPeriod: string;
  status: string;
  startedAt: string;
  endsAt: string | null;
}

export interface OrganizationSubscriptionRequest {
  planId: string;
  seatLimit: number;
  billingPeriod?: 'MONTHLY' | 'QUARTERLY' | 'ANNUAL';
}
