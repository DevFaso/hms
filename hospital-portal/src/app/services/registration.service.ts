import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Multi-hospital patient registrations — /registrations (bare DTOs; the list
 * endpoint returns a PLAIN ARRAY sliced server-side by page/size — there is
 * no Page envelope and no total count). Roles: create/update/delete =
 * RECEPTIONIST/HOSPITAL_ADMIN; reads add DOCTOR/NURSE/MIDWIFE/SUPER_ADMIN;
 * the multi-hospital summary excludes RECEPTIONIST (cross-tenant data).
 * PUT overwrites every editable field (send the full object). DELETE is a
 * HARD delete — deactivation is PUT with active:false.
 */

export type PatientStayStatus =
  | 'REGISTERED'
  | 'ADMITTED'
  | 'READY_FOR_DISCHARGE'
  | 'DISCHARGED'
  | 'TRANSFERRED'
  | 'HOLD';

export interface HospitalRegistration {
  id: string;
  /** MRN; the backend also serializes the same value as registrationCode. */
  mri?: string;
  registrationCode?: string;
  patientId?: string;
  patientUsername?: string;
  patientFirstName?: string;
  patientLastName?: string;
  patientFullName?: string;
  patientEmail?: string;
  patientPhone?: string;
  patientGender?: string;
  hospitalId?: string;
  hospitalName?: string;
  hospitalCode?: string;
  hospitalAddress?: string;
  registrationDate?: string;
  active: boolean;
  stayStatus?: PatientStayStatus;
  stayStatusUpdatedAt?: string;
  currentRoom?: string;
  currentBed?: string;
  attendingPhysicianName?: string;
  readyForDischargeNote?: string;
  readyByStaffId?: string;
}

export interface RegistrationRequest {
  /** Username OR email; preferred over patientId. */
  patientUsername?: string;
  /** Exact hospital name; preferred over hospitalId (defaults to JWT hospital). */
  hospitalName?: string;
  patientId?: string;
  hospitalId?: string;
  active?: boolean;
  currentRoom?: string;
  currentBed?: string;
  attendingPhysicianName?: string;
  stayStatus?: PatientStayStatus;
  readyForDischargeNote?: string;
}

export interface MultiHospitalSummaryRow {
  patientId: string;
  patientName?: string;
  hospitalId: string;
  hospitalName?: string;
}

@Injectable({ providedIn: 'root' })
export class RegistrationService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/registrations';

  create(req: RegistrationRequest): Observable<HospitalRegistration> {
    return this.http.post<HospitalRegistration>(this.baseUrl, req);
  }

  getById(id: string): Observable<HospitalRegistration> {
    return this.http.get<HospitalRegistration>(`${this.baseUrl}/${id}`);
  }

  /**
   * Bare array (no Page envelope). When patientId is set the backend ignores
   * hospitalId; with neither, it scopes to the JWT hospital.
   */
  list(params: {
    patientId?: string;
    hospitalId?: string;
    active?: boolean;
    page?: number;
    size?: number;
  }): Observable<HospitalRegistration[]> {
    let httpParams = new HttpParams();
    if (params.patientId) httpParams = httpParams.set('patientId', params.patientId);
    if (params.hospitalId) httpParams = httpParams.set('hospitalId', params.hospitalId);
    if (params.active !== undefined) httpParams = httpParams.set('active', params.active);
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page);
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size);
    return this.http.get<HospitalRegistration[]>(this.baseUrl, { params: httpParams });
  }

  /** Patients active in >1 hospital — excludes RECEPTIONIST on the backend. */
  multiHospital(): Observable<MultiHospitalSummaryRow[]> {
    return this.http.get<MultiHospitalSummaryRow[]>(`${this.baseUrl}/multi-hospital`);
  }

  /** Full replace of editable fields — send the complete object. */
  update(id: string, req: RegistrationRequest): Observable<HospitalRegistration> {
    return this.http.put<HospitalRegistration>(`${this.baseUrl}/${id}`, req);
  }

  /** HARD delete. Use update({active:false}) to deactivate instead. */
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
