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
 *  - payerCode/verifiedAt/verifiedBy are not in the response DTO (the
 *    reception snapshot carries verification info instead).
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
}

export interface LinkInsuranceRequest {
  patientId: string;
  hospitalId?: string;
  /** true = make primary (unsets others), false = unset, null/undefined = keep. */
  primary?: boolean;
  payerCode: string;
  policyNumber: string;
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

  /** Links an existing policy (payerCode + policyNumber) to the patient. */
  link(req: LinkInsuranceRequest): Observable<PatientInsurance> {
    return this.http.put<PatientInsurance>(`${this.baseUrl}/link`, req);
  }

  update(insuranceId: string, req: PatientInsuranceUpdateRequest): Observable<PatientInsurance> {
    return this.http.put<PatientInsurance>(`${this.baseUrl}/${insuranceId}`, req);
  }

  /** Backend responds 200 text/plain — request text to avoid a JSON parse error. */
  delete(insuranceId: string): Observable<string> {
    return this.http.delete(`${this.baseUrl}/${insuranceId}`, { responseType: 'text' });
  }
}
