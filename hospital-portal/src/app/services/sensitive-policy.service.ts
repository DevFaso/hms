import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { SensitivityCategory } from '../shared/sensitivity/sensitivity-category';

/**
 * Policy 2026-11-20 (provisional, awaiting a jurist's and a clinician's
 * sign-off): the sensitive-data defaults and the release of a minor's result.
 */

/** The backend's LabSensitivityRecommendationDTO: one test the curated list recommends a category for. */
export interface LabSensitivityRecommendation {
  definitionId: string;
  testCode: string;
  name: string;
  panel: boolean;
  currentCategory: SensitivityCategory | null;
  recommendedCategory: SensitivityCategory;
  matchedOn: 'CODE' | 'NAME' | 'COMPONENT';
  matchedText: string | null;
  applied: boolean;
  provisional: boolean;
}

export interface LabSensitivityRecommendationChoice {
  definitionId: string;
  category: SensitivityCategory;
}

export type GuardianRelationship = 'PARENT' | 'LEGAL_GUARDIAN';

/** A parent's or guardian's family-access grant. */
export interface FamilyReleaseGuardian {
  grantId: string;
  holderName: string | null;
  relationship: GuardianRelationship;
  verified: boolean;
  verifiedAt: string | null;
}

export interface FamilyReleaseRecord {
  grantId: string;
  holderName: string | null;
  releasedAt: string;
  /** What the clinician recorded; shown to the clinicians only. */
  reason: string | null;
}

/** A released lab result a proxy does not read, with the grants it was released to. */
export interface FamilyHiddenResult {
  labOrderId: string;
  testName: string | null;
  category: SensitivityCategory;
  resultedAt: string | null;
  /** A clinician may release it: an HIV result only, never one that violence is on. */
  releasable: boolean;
  /**
   * r66 D2903: why it cannot be released. VIOLENCE: violence is on the visit or
   * the test (never released to the family); NOT_HIV: only an HIV result is.
   * Absent or null when it is releasable.
   */
  notReleasableReason?: 'VIOLENCE' | 'NOT_HIV' | null;
  releases: FamilyReleaseRecord[];
}

/** The backend's MinorReleaseOverviewDTO. */
export interface FamilyReleaseOverview {
  patientId: string;
  minor: boolean;
  adolescentAge: number;
  adolescentAgeIsDefault: boolean;
  guardians: FamilyReleaseGuardian[];
  results: FamilyHiddenResult[];
}

export interface FamilyReleaseRequest {
  grantId: string;
  reason: string;
  relationshipVerified: boolean;
}

/** The backend's AdolescentConfidentialityAgeDTO. */
export interface AdolescentConfidentialityAge {
  hospitalId: string;
  configuredAge: number | null;
  effectiveAge: number;
  defaultAge: number;
  provisional: boolean;
}

interface ApiWrapper<T> {
  data: T;
  success: boolean;
}

@Injectable({ providedIn: 'root' })
export class SensitivePolicyService {
  private readonly http = inject(HttpClient);

  /** The tests of the catalogue the curated list recommends a sensitive category for. Reads only. */
  labRecommendations(): Observable<LabSensitivityRecommendation[]> {
    return this.http
      .get<ApiWrapper<LabSensitivityRecommendation[]>>(
        '/lab-test-definitions/sensitivity-recommendations',
      )
      .pipe(map((res) => res.data ?? []));
  }

  /** Tags the tests the lab director confirmed; each is audited. */
  applyLabRecommendations(
    items: LabSensitivityRecommendationChoice[],
  ): Observable<LabSensitivityRecommendation[]> {
    return this.http
      .post<ApiWrapper<LabSensitivityRecommendation[]>>(
        '/lab-test-definitions/sensitivity-recommendations/apply',
        { items },
      )
      .pipe(map((res) => res.data ?? []));
  }

  /** The parents' grants and the results hidden from them, for a patient under 18. */
  familyReleases(patientId: string): Observable<FamilyReleaseOverview> {
    return this.http.get<FamilyReleaseOverview>(`/patients/${patientId}/family-releases`);
  }

  verifyGuardian(patientId: string, grantId: string): Observable<FamilyReleaseOverview> {
    return this.http.post<FamilyReleaseOverview>(
      `/patients/${patientId}/family-releases/guardians/${grantId}/verified`,
      {},
    );
  }

  releaseResult(
    patientId: string,
    labOrderId: string,
    request: FamilyReleaseRequest,
  ): Observable<FamilyReleaseOverview> {
    return this.http.post<FamilyReleaseOverview>(
      `/patients/${patientId}/family-releases/lab-orders/${labOrderId}`,
      request,
    );
  }

  adolescentAge(hospitalId: string): Observable<AdolescentConfidentialityAge> {
    return this.http.get<AdolescentConfidentialityAge>(
      `/hospitals/${hospitalId}/adolescent-confidentiality-age`,
    );
  }

  /** `null` returns the hospital to the provisional default. */
  setAdolescentAge(
    hospitalId: string,
    age: number | null,
  ): Observable<AdolescentConfidentialityAge> {
    return this.http.put<AdolescentConfidentialityAge>(
      `/hospitals/${hospitalId}/adolescent-confidentiality-age`,
      { age },
    );
  }
}
