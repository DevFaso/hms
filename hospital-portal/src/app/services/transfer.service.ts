import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { IsolationPrecautionType } from './isolation.service';

export type TransferOrderStatus = 'REQUESTED' | 'COMPLETED' | 'CANCELLED';

export type TransferType = 'BED_TO_BED' | 'WARD_TO_WARD';

export interface TransferOrderResponse {
  id: string;
  admissionId: string;
  patientId: string;
  patientName: string | null;
  mrn: string | null;
  /** Where the patient was when the order was raised — a snapshot, not a join. */
  fromBedId: string | null;
  fromBedLabel: string | null;
  fromWardName: string | null;
  toBedId: string;
  toBedLabel: string | null;
  toWardName: string | null;
  transferType: TransferType;
  status: TransferOrderStatus;
  reason: string;
  notes: string | null;
  requestedByName: string | null;
  requestedAt: string;
  completedByName: string | null;
  completedAt: string | null;
  cancelledByName: string | null;
  cancelledAt: string | null;
  cancellationReason: string | null;
  isolationOverride: boolean;
  isolationOverrideReason: string | null;
  /** Shown on the worklist so a porter sees them without opening the chart. */
  isolationPrecautions: IsolationPrecautionType[];
  destinationIsolationMismatch: boolean;
}

export interface TransferOrderRequest {
  admissionId: string;
  toBedId: string;
  reason: string;
  notes?: string;
  requestedByStaffId?: string;
  /** Move despite the destination not containing an active airborne precaution. */
  isolationOverride?: boolean;
  /** Required by the backend when isolationOverride is true. */
  isolationOverrideReason?: string;
}

export interface TransferCompletionRequest {
  completedByStaffId?: string;
  notes?: string;
}

/** The destination has been held out of circulation, so a reason is required. */
export interface TransferCancellationRequest {
  cancellationReason: string;
  cancelledByStaffId?: string;
}

@Injectable({ providedIn: 'root' })
export class TransferService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/transfers';

  requestTransfer(req: TransferOrderRequest): Observable<TransferOrderResponse> {
    return this.http.post<TransferOrderResponse>(this.baseUrl, req);
  }

  completeTransfer(
    orderId: string,
    req: TransferCompletionRequest,
  ): Observable<TransferOrderResponse> {
    return this.http.post<TransferOrderResponse>(`${this.baseUrl}/${orderId}/complete`, req);
  }

  cancelTransfer(
    orderId: string,
    req: TransferCancellationRequest,
  ): Observable<TransferOrderResponse> {
    return this.http.post<TransferOrderResponse>(`${this.baseUrl}/${orderId}/cancel`, req);
  }

  getPending(): Observable<TransferOrderResponse[]> {
    return this.http.get<TransferOrderResponse[]>(`${this.baseUrl}/pending`);
  }

  getHistoryForAdmission(admissionId: string): Observable<TransferOrderResponse[]> {
    return this.http.get<TransferOrderResponse[]>(`${this.baseUrl}/admission/${admissionId}`);
  }
}
