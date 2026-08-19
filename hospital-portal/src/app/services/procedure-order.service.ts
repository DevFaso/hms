import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Procedure orders — /procedure-orders (bare DTOs, List only, no pagination).
 * Effective roles (permission authorities are never granted): create/update =
 * DOCTOR/NURSE/SUPER_ADMIN; hospital lists = + HOSPITAL_ADMIN; cancel =
 * DOCTOR/SUPER_ADMIN only. HOSPITAL_ADMIN cannot GET a single order — the UI
 * renders detail from the list row instead of fetching by id.
 * Lifecycle: create forces ORDERED; PUT {scheduledDatetime} auto-promotes
 * ORDERED→SCHEDULED; consent is recorded via PUT and does NOT change status
 * (pending-consent = SCHEDULED && !consentObtained); COMPLETED via
 * PUT {status}; cancel is a POST with a REQUIRED cancellationReason query
 * param. COMPLETED/CANCELLED are terminal (further updates 400).
 */

export type ProcedureOrderStatus =
  | 'ORDERED'
  | 'SCHEDULED'
  | 'PRE_OP_CLEARANCE_PENDING'
  | 'READY_FOR_PROCEDURE'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'POSTPONED';
export type ProcedureUrgency = 'ROUTINE' | 'URGENT' | 'EMERGENT' | 'ELECTIVE';

export interface ProcedureOrderBase {
  patientId?: string;
  hospitalId?: string;
  encounterId?: string;
  procedureCode?: string;
  procedureName?: string;
  procedureCategory?: string;
  indication?: string;
  clinicalNotes?: string;
  urgency?: ProcedureUrgency;
  scheduledDatetime?: string | null;
  estimatedDurationMinutes?: number;
  requiresAnesthesia?: boolean;
  anesthesiaType?: string;
  requiresSedation?: boolean;
  sedationType?: string;
  preProcedureInstructions?: string;
  consentObtained?: boolean;
  consentObtainedAt?: string | null;
  consentObtainedBy?: string;
  consentFormLocation?: string;
  /** Free-text on the backend (no enum); UI offers LEFT/RIGHT/BILATERAL/N-A. */
  laterality?: string;
  siteMarked?: boolean;
  specialEquipmentNeeded?: string;
  bloodProductsRequired?: boolean;
  imagingGuidanceRequired?: boolean;
}

export interface ProcedureOrderRequest extends ProcedureOrderBase {
  patientId: string;
  hospitalId: string;
  procedureName: string;
  indication: string;
  urgency: ProcedureUrgency;
}

export interface ProcedureOrderResponse extends ProcedureOrderBase {
  id: string;
  patientId: string;
  hospitalId: string;
  patientName?: string;
  patientMrn?: string;
  hospitalName?: string;
  orderingProviderId?: string;
  orderingProviderName?: string;
  status: ProcedureOrderStatus;
  orderedAt?: string;
  cancelledAt?: string | null;
  cancellationReason?: string | null;
  completedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

/** Only these groups are applied by the backend update — the rest is ignored. */
export interface ProcedureOrderUpdate {
  status?: ProcedureOrderStatus;
  scheduledDatetime?: string;
  consentObtained?: boolean;
  consentObtainedAt?: string;
  consentObtainedBy?: string;
  consentFormLocation?: string;
  siteMarked?: boolean;
}

@Injectable({ providedIn: 'root' })
export class ProcedureOrderService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/procedure-orders';

  create(req: ProcedureOrderRequest): Observable<ProcedureOrderResponse> {
    return this.http.post<ProcedureOrderResponse>(this.baseUrl, req);
  }

  byHospital(
    hospitalId: string,
    status?: ProcedureOrderStatus,
  ): Observable<ProcedureOrderResponse[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<ProcedureOrderResponse[]>(`${this.baseUrl}/hospital/${hospitalId}`, {
      params,
    });
  }

  /** SCHEDULED orders without recorded consent. */
  pendingConsent(hospitalId: string): Observable<ProcedureOrderResponse[]> {
    return this.http.get<ProcedureOrderResponse[]>(
      `${this.baseUrl}/hospital/${hospitalId}/pending-consent`,
    );
  }

  update(orderId: string, update: ProcedureOrderUpdate): Observable<ProcedureOrderResponse> {
    return this.http.put<ProcedureOrderResponse>(`${this.baseUrl}/${orderId}`, update);
  }

  /** DOCTOR/SUPER_ADMIN only. Reason travels as a query param, not a body. */
  cancel(orderId: string, cancellationReason: string): Observable<ProcedureOrderResponse> {
    const params = new HttpParams().set('cancellationReason', cancellationReason);
    return this.http.post<ProcedureOrderResponse>(`${this.baseUrl}/${orderId}/cancel`, null, {
      params,
    });
  }
}
