import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface OnboardingStep {
  key: string;
  label: string;
  completed: boolean;
  detail: string;
}

export interface TenantOnboardingStatus {
  organizationId: string;
  organizationName: string;
  organizationCode: string;
  completedSteps: number;
  totalSteps: number;
  steps: OnboardingStep[];
}

@Injectable({ providedIn: 'root' })
export class TenantOnboardingService {
  private readonly http = inject(HttpClient);

  getOnboardingStatus(organizationId: string): Observable<TenantOnboardingStatus> {
    return this.http.get<TenantOnboardingStatus>(
      `/super-admin/organizations/${organizationId}/onboarding`,
    );
  }
}
