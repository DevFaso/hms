import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type AboGroup = 'A' | 'B' | 'AB' | 'O';
export type RhFactor = 'POSITIVE' | 'NEGATIVE';
export type AntibodyScreenResult = 'NEGATIVE' | 'POSITIVE' | 'NOT_DONE';

export type BloodProductType =
  'WHOLE_BLOOD' | 'PACKED_RED_CELLS' | 'FRESH_FROZEN_PLASMA' | 'PLATELETS' | 'CRYOPRECIPITATE';

export type BloodUnitStatus =
  'AVAILABLE' | 'CROSSMATCHED' | 'ISSUED' | 'TRANSFUSED' | 'RETURNED' | 'DISCARDED' | 'EXPIRED';

export type TransfusionRequestStatus =
  'REQUESTED' | 'CROSSMATCHED' | 'ISSUED' | 'COMPLETED' | 'CANCELLED';

export type TransfusionUrgency = 'ROUTINE' | 'URGENT' | 'EMERGENCY';

export type TransfusionAdministrationStatus = 'IN_PROGRESS' | 'COMPLETED' | 'STOPPED';

export type TransfusionReactionType =
  | 'FEBRILE_NON_HEMOLYTIC'
  | 'ACUTE_HEMOLYTIC'
  | 'DELAYED_HEMOLYTIC'
  | 'ALLERGIC'
  | 'ANAPHYLACTIC'
  | 'TACO'
  | 'TRALI'
  | 'SEPTIC'
  | 'HYPOTENSIVE'
  | 'OTHER';

export type TransfusionReactionSeverity = 'MILD' | 'MODERATE' | 'SEVERE' | 'LIFE_THREATENING';

export interface PatientBloodGroupResponse {
  id: string;
  patientId: string;
  patientName: string;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  antibodyScreen: AntibodyScreenResult;
  antibodyDetail: string | null;
  specimenCollectedAt: string | null;
  performedAt: string;
  expiresAt: string | null;
  performedByName: string | null;
  superseded: boolean;
  /** The SCREEN is still usable for a crossmatch. ABO/Rh never expires. */
  screenCurrent: boolean;
  notes: string | null;
}

export interface PatientBloodGroupRequest {
  patientId: string;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  antibodyScreen: AntibodyScreenResult;
  antibodyDetail?: string;
  specimenCollectedAt?: string;
  expiresAt?: string;
  /** Required when the ABO/Rh differs from the standing group. */
  correctionReason?: string;
  notes?: string;
}

export interface BloodUnitResponse {
  id: string;
  requestId: string | null;
  unitNumber: string;
  productType: BloodProductType;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  volumeMl: number | null;
  collectedOn: string | null;
  expiresOn: string;
  expired: boolean;
  source: string | null;
  status: BloodUnitStatus;
  discardReason: string | null;
  notes: string | null;
}

export interface BloodUnitRequest {
  unitNumber: string;
  productType: BloodProductType;
  aboGroup: AboGroup;
  rhFactor: RhFactor;
  volumeMl?: number;
  collectedOn?: string;
  expiresOn: string;
  source?: string;
  requestId?: string;
  notes?: string;
}

export interface CrossmatchResponse {
  id: string;
  requestId: string;
  bloodUnitId: string;
  unitNumber: string;
  compatible: boolean;
  method: string | null;
  incompatibilityReason: string | null;
  performedByName: string | null;
  performedAt: string;
  expiresAt: string | null;
  /** Compatible AND unexpired — what the issue path actually requires. */
  usable: boolean;
}

export interface CrossmatchRequest {
  bloodUnitId: string;
  compatible: boolean;
  method?: string;
  incompatibilityReason?: string;
  expiresAt?: string;
}

export interface TransfusionReactionResponse {
  id: string;
  administrationId: string;
  patientId: string;
  patientName: string;
  reactionType: TransfusionReactionType;
  severity: TransfusionReactionSeverity;
  onsetAt: string;
  signsSymptoms: string;
  actionsTaken: string | null;
  unitReturnedToLab: boolean;
  reportedByName: string | null;
  reportedAt: string;
  severe: boolean;
}

export interface TransfusionReactionRequest {
  reactionType: TransfusionReactionType;
  severity: TransfusionReactionSeverity;
  onsetAt: string;
  signsSymptoms: string;
  actionsTaken?: string;
  unitReturnedToLab?: boolean;
}

export interface TransfusionAdministrationResponse {
  id: string;
  requestId: string;
  bloodUnitId: string;
  unitNumber: string;
  patientId: string;
  patientName: string;
  status: TransfusionAdministrationStatus;
  startedAt: string;
  completedAt: string | null;
  volumeTransfusedMl: number | null;
  administeredByName: string | null;
  verifiedByName: string | null;
  verificationMethod: string | null;
  stopReason: string | null;
  notes: string | null;
  reactions: TransfusionReactionResponse[];
}

export interface TransfusionAdministrationRequest {
  requestId: string;
  bloodUnitId: string;
  /** The independent second bedside check. Never the caller themselves. */
  verifiedByStaffId: string;
  verificationMethod?: string;
  notes?: string;
}

export interface TransfusionRequestResponse {
  id: string;
  patientId: string;
  patientName: string;
  patientMrn: string | null;
  encounterId: string | null;
  productType: BloodProductType;
  unitsRequested: number;
  indication: string;
  urgency: TransfusionUrgency;
  status: TransfusionRequestStatus;
  requestedByName: string | null;
  requestedAt: string;
  requiredBy: string | null;
  cancelReason: string | null;
  notes: string | null;
  bloodGroupId: string | null;
  patientAboGroup: AboGroup | null;
  patientRhFactor: RhFactor | null;
  screenCurrent: boolean;
  units: BloodUnitResponse[];
  crossmatches: CrossmatchResponse[];
}

export interface TransfusionRequestRequest {
  patientId: string;
  encounterId?: string;
  productType: BloodProductType;
  unitsRequested: number;
  indication: string;
  urgency?: TransfusionUrgency;
  requiredBy?: string;
  notes?: string;
}

/**
 * Blood bank and transfusion (Tier 2 item 28).
 *
 * <p>Compatibility is decided by the backend and never mirrored here. A
 * client-side ABO check would be a second implementation of the rule that
 * kills people when it drifts from the first, so the UI asks and reports.
 */
@Injectable({ providedIn: 'root' })
export class TransfusionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/transfusions';

  /* ── Type and screen ── */

  recordBloodGroup(req: PatientBloodGroupRequest): Observable<PatientBloodGroupResponse> {
    return this.http.post<PatientBloodGroupResponse>(`${this.baseUrl}/blood-groups`, req);
  }

  getCurrentBloodGroup(patientId: string): Observable<PatientBloodGroupResponse> {
    return this.http.get<PatientBloodGroupResponse>(
      `${this.baseUrl}/blood-groups/patient/${patientId}`,
    );
  }

  getBloodGroupHistory(patientId: string): Observable<PatientBloodGroupResponse[]> {
    return this.http.get<PatientBloodGroupResponse[]>(
      `${this.baseUrl}/blood-groups/patient/${patientId}/history`,
    );
  }

  /* ── Units ── */

  receiveUnit(req: BloodUnitRequest): Observable<BloodUnitResponse> {
    return this.http.post<BloodUnitResponse>(`${this.baseUrl}/units`, req);
  }

  listUnits(status?: BloodUnitStatus): Observable<BloodUnitResponse[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<BloodUnitResponse[]>(`${this.baseUrl}/units`, { params });
  }

  listAssignableUnits(): Observable<BloodUnitResponse[]> {
    return this.http.get<BloodUnitResponse[]>(`${this.baseUrl}/units/assignable`);
  }

  discardUnit(unitId: string, reason: string): Observable<BloodUnitResponse> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<BloodUnitResponse>(`${this.baseUrl}/units/${unitId}/discard`, null, {
      params,
    });
  }

  /* ── Requests ── */

  createRequest(req: TransfusionRequestRequest): Observable<TransfusionRequestResponse> {
    return this.http.post<TransfusionRequestResponse>(`${this.baseUrl}/requests`, req);
  }

  getRequest(requestId: string): Observable<TransfusionRequestResponse> {
    return this.http.get<TransfusionRequestResponse>(`${this.baseUrl}/requests/${requestId}`);
  }

  listRequests(status?: TransfusionRequestStatus): Observable<TransfusionRequestResponse[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<TransfusionRequestResponse[]>(`${this.baseUrl}/requests`, { params });
  }

  listRequestsForPatient(patientId: string): Observable<TransfusionRequestResponse[]> {
    return this.http.get<TransfusionRequestResponse[]>(
      `${this.baseUrl}/requests/patient/${patientId}`,
    );
  }

  cancelRequest(requestId: string, reason: string): Observable<TransfusionRequestResponse> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<TransfusionRequestResponse>(
      `${this.baseUrl}/requests/${requestId}/cancel`,
      null,
      { params },
    );
  }

  /* ── Crossmatch and issue ── */

  recordCrossmatch(requestId: string, req: CrossmatchRequest): Observable<CrossmatchResponse> {
    return this.http.post<CrossmatchResponse>(
      `${this.baseUrl}/requests/${requestId}/crossmatch`,
      req,
    );
  }

  listCrossmatches(requestId: string): Observable<CrossmatchResponse[]> {
    return this.http.get<CrossmatchResponse[]>(`${this.baseUrl}/requests/${requestId}/crossmatch`);
  }

  issueUnit(requestId: string, unitId: string): Observable<BloodUnitResponse> {
    return this.http.post<BloodUnitResponse>(
      `${this.baseUrl}/requests/${requestId}/issue/${unitId}`,
      null,
    );
  }

  /* ── Bedside ── */

  startAdministration(
    req: TransfusionAdministrationRequest,
  ): Observable<TransfusionAdministrationResponse> {
    return this.http.post<TransfusionAdministrationResponse>(
      `${this.baseUrl}/administrations`,
      req,
    );
  }

  completeAdministration(
    administrationId: string,
    volumeMl?: number,
  ): Observable<TransfusionAdministrationResponse> {
    let params = new HttpParams();
    if (volumeMl != null) params = params.set('volumeMl', String(volumeMl));
    return this.http.post<TransfusionAdministrationResponse>(
      `${this.baseUrl}/administrations/${administrationId}/complete`,
      null,
      { params },
    );
  }

  stopAdministration(
    administrationId: string,
    reason: string,
  ): Observable<TransfusionAdministrationResponse> {
    const params = new HttpParams().set('reason', reason);
    return this.http.post<TransfusionAdministrationResponse>(
      `${this.baseUrl}/administrations/${administrationId}/stop`,
      null,
      { params },
    );
  }

  listAdministrationsForPatient(
    patientId: string,
  ): Observable<TransfusionAdministrationResponse[]> {
    return this.http.get<TransfusionAdministrationResponse[]>(
      `${this.baseUrl}/administrations/patient/${patientId}`,
    );
  }

  recordReaction(
    administrationId: string,
    req: TransfusionReactionRequest,
  ): Observable<TransfusionReactionResponse> {
    return this.http.post<TransfusionReactionResponse>(
      `${this.baseUrl}/administrations/${administrationId}/reaction`,
      req,
    );
  }

  listReactionsForPatient(patientId: string): Observable<TransfusionReactionResponse[]> {
    return this.http.get<TransfusionReactionResponse[]>(
      `${this.baseUrl}/reactions/patient/${patientId}`,
    );
  }
}
