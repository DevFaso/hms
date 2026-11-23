import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Patient insurance — /patient-insurances (bare DTOs, no wrapper/paging).
 * Backend quirks the UI designs around:
 *  - Roles are HOSPITAL_ADMIN/RECEPTIONIST/NURSE/DOCTOR (+PATIENT on link/
 *    read). SUPER_ADMIN is NOT granted — hide the panel for super admins.
 *  - PUT /{id} cannot change the primary flag (mapper ignores it); primary is
 *    changed via PUT /link with the policy's payerCode + policyNumber.
 *  - DELETE returns 200 text/plain (an i18n message), not JSON/204.
 *  - payerCode is not in the response DTO. QA D3150: verifiedAt and the
 *    verifier's name (never their login) are.
 */

export interface PatientInsurance {
  id: string;
  patientId: string;
  hospitalId?: string;
  assignmentId?: string;
  linkedByUserId?: string;
  /** 'PATIENT' | 'STAFF' as written by the backend. */
  linkedAs?: string;
  providerName?: string;
  policyNumber?: string;
  groupNumber?: string;
  subscriberName?: string;
  subscriberRelationship?: string;
  effectiveDate?: string;
  expirationDate?: string;
  primary?: boolean;
  createdAt?: string;
  updatedAt?: string;
  /** Billing-money plan PR 6: the payer plan (formule) this coverage is billed under; absent for free text. */
  payerPlanId?: string | null;
  payerPlanCode?: string | null;
  /** As the hospital typed it: never translated. */
  payerPlanName?: string | null;
  planPayerCode?: string | null;
  /** As the hospital typed it: never translated. */
  planPayerName?: string | null;
  /** QA D3150: when the desk last verified this coverage. */
  verifiedAt?: string | null;
  /** QA D3150: who verified it, by name (never their login). */
  verifiedByName?: string | null;
}

/**
 * Owner decision 2026-11-22: a policy recorded at another hospital, shown
 * « à vérifier » by its identity only. The source hospital's claims, amounts,
 * approvals, documents and notes are never sent.
 */
export interface OtherHospitalPolicy {
  /** Named only to reuse it: the row itself is never readable here. */
  sourceInsuranceId: string;
  /** As the other hospital is named: never translated. */
  sourceHospitalName?: string | null;
  insurerName?: string | null;
  policyNumber?: string | null;
  planName?: string | null;
  holderRelationship?: string | null;
  effectiveDate?: string | null;
  expirationDate?: string | null;
  /** The day the other hospital recorded it: « Déclarée à … le … », never « vérifiée ». */
  declaredOn?: string | null;
  /** Whether this hospital has an active agreement (payer) with that insurer. */
  agreementHere: boolean;
}

export interface LinkInsuranceRequest {
  patientId: string;
  hospitalId?: string;
  /** true = make primary (unsets others), false = unset, null/undefined = keep. */
  primary?: boolean;
  payerCode: string;
  policyNumber: string;
  /** QA D925: the insurer's name; a new policy is named after its payer when absent. */
  providerName?: string;
  /** Billing-money plan PR 6: a payer plan of the acting hospital; absent leaves the link as it is. */
  payerPlanId?: string;
}

export interface PatientInsuranceUpdateRequest {
  patientId: string;
  providerName: string;
  policyNumber: string;
  groupNumber?: string;
  subscriberName?: string;
  subscriberRelationship?: string;
  effectiveDate?: string;
  expirationDate?: string;
  primary?: boolean;
}

@Injectable({ providedIn: 'root' })
export class PatientInsuranceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/patient-insurances';

  forPatient(patientId: string): Observable<PatientInsurance[]> {
    return this.http.get<PatientInsurance[]>(`${this.baseUrl}/patient/${patientId}`);
  }

  /**
   * Owner decision 2026-11-22: the patient's policies recorded at other
   * hospitals this one may read (hospital admin, billing, reception only).
   */
  otherHospitals(patientId: string): Observable<OtherHospitalPolicy[]> {
    return this.http.get<OtherHospitalPolicy[]>(
      `${this.baseUrl}/patient/${patientId}/other-hospitals`,
    );
  }

  /** « Reprendre pour cet établissement »: this hospital's own, unverified coverage. */
  reuseOtherHospital(sourceInsuranceId: string): Observable<PatientInsurance> {
    return this.http.post<PatientInsurance>(`${this.baseUrl}/${sourceInsuranceId}/reuse`, {});
  }

  /** Links an existing policy (payerCode + policyNumber) to the patient. */
  link(req: LinkInsuranceRequest): Observable<PatientInsurance> {
    return this.http.put<PatientInsurance>(`${this.baseUrl}/link`, req);
  }

  update(insuranceId: string, req: PatientInsuranceUpdateRequest): Observable<PatientInsurance> {
    return this.http.put<PatientInsurance>(`${this.baseUrl}/${insuranceId}`, req);
  }

  /**
   * Billing-money plan PR 6: the "Formule" picker. Links the coverage to a
   * payer plan of the acting hospital, or removes the link (null). Hospital
   * admin, billing specialist and reception only.
   */
  setPayerPlan(insuranceId: string, payerPlanId: string | null): Observable<PatientInsurance> {
    return this.http.put<PatientInsurance>(`${this.baseUrl}/${insuranceId}/payer-plan`, {
      payerPlanId,
    });
  }

  /** Backend responds 200 text/plain — request text to avoid a JSON parse error. */
  delete(insuranceId: string): Observable<string> {
    return this.http.delete(`${this.baseUrl}/${insuranceId}`, { responseType: 'text' });
  }
}
