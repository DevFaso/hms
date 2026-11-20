import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MobileMoneyOperator } from '../shared/mobile-money-operator';
import { ApiResponse, InventoryItemResponse } from './pharmacy.service';

/* QA r65 D2673–D2676: pharmacy money and stock (reconciliation, refunds, pack size, payment status). */

export type DispensePaymentState = 'NO_CHARGE' | 'PAID' | 'COVERED' | 'DEFERRED' | 'UNPAID';

/** QA D2675: what a fill costs and how it is settled. */
export interface DispensePaymentStatus {
  dispenseId: string;
  quantity: number;
  unit?: string | null;
  charge: number;
  paid: number;
  claimed: number;
  due: number;
  status: DispensePaymentState;
  billingDeferredReason?: string | null;
  billingDeferredAt?: string | null;
}

/** QA D2674: set once, the stock is counted in `unit`, `unitsPerPack` to a pack. */
export interface PackSizeRequest {
  unitsPerPack: number;
  unit: string;
  packUnit?: string;
  reason: string;
}

export type PharmacyRefundStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'PAID' | 'CANCELLED';

export interface PharmacyRefund {
  id: string;
  pharmacyId: string;
  returnTransactionId: string;
  saleId?: string | null;
  dispenseId?: string | null;
  medicationName?: string | null;
  quantity: number;
  amount: number;
  method: 'CASH' | 'MOBILE_MONEY';
  mobileMoneyOperator?: MobileMoneyOperator | null;
  reference?: string | null;
  reason: string;
  status: PharmacyRefundStatus;
  requestedBy: string;
  requestedByName?: string | null;
  requestedAt: string;
  decidedAt?: string | null;
  decisionReason?: string | null;
  paidAt?: string | null;
  cancelReason?: string | null;
  canDecide: boolean;
}

export interface PharmacyRefundRequest {
  returnTransactionId: string;
  reason: string;
  method?: 'CASH' | 'MOBILE_MONEY';
  mobileMoneyOperator?: MobileMoneyOperator;
  reference?: string;
}

export type ReconciliationDifferenceType =
  | 'SALE_STOCK_MISMATCH'
  | 'DISPENSE_STOCK_MISMATCH'
  | 'PRICE_NOT_LIST'
  | 'UNPAID_HANDED_OVER'
  | 'CONTROLLED_BALANCE';

export interface PharmacyReconciliation {
  pharmacyId: string;
  pharmacyName?: string;
  date: string;
  generatedAt: string;
  money: {
    salesCount: number;
    salesTotal: number;
    salesByMethod: Record<string, number>;
    dispensePaymentsCount: number;
    dispensePaymentsTotal: number;
    dispensePaymentsByMethod: Record<string, number>;
    refundsCount: number;
    refundsPaid: number;
    netCollected: number;
    dispenseCharges: number;
    deferredTotal: number;
    unpaidTotal: number;
  };
  stock: {
    inventoryItemId?: string | null;
    medicationName?: string | null;
    unit?: string | null;
    stockLotId?: string | null;
    lotNumber?: string | null;
    received: number;
    soldRecorded: number;
    soldMoved: number;
    dispensedRecorded: number;
    dispensedMoved: number;
    returned: number;
    adjusted: number;
    transferred: number;
    difference: boolean;
  }[];
  differences: {
    type: ReconciliationDifferenceType;
    medicationName?: string | null;
    lotNumber?: string | null;
    referenceId?: string | null;
    expected?: number | null;
    actual?: number | null;
    amount?: number | null;
  }[];
  controlled: {
    inventoryItemId: string;
    medicationName?: string | null;
    registerBalance: number;
    stockBalance: number;
    matches: boolean;
  }[];
  deferred: {
    dispenseId: string;
    medicationName?: string | null;
    quantity: number;
    due: number;
    reason: string;
    deferredAt?: string | null;
  }[];
  /** Review of #1239: every lot over the day, opening + movements against its closing (also in the CSV). */
  lotBalances?: {
    stockLotId: string;
    medicationName?: string | null;
    lotNumber?: string | null;
    opening: number;
    movements: number;
    closing: number;
    matches: boolean;
  }[];
}

@Injectable({ providedIn: 'root' })
export class PharmacyMoneyService {
  private readonly http = inject(HttpClient);

  dispensePaymentStatus(dispenseId: string): Observable<ApiResponse<DispensePaymentStatus>> {
    return this.http.get<ApiResponse<DispensePaymentStatus>>(
      `/pharmacy/dispense/${dispenseId}/payment-status`,
    );
  }

  setPackSize(
    itemId: string,
    body: PackSizeRequest,
  ): Observable<ApiResponse<InventoryItemResponse>> {
    return this.http.put<ApiResponse<InventoryItemResponse>>(
      `/pharmacy/inventory/items/${itemId}/pack-size`,
      body,
    );
  }

  /** QA D2673: the pharmacies a reader may reconcile (an accountant cannot read the registry). */
  reconciliationPharmacies(): Observable<
    ApiResponse<{ pharmacyId: string; pharmacyName: string }[]>
  > {
    return this.http.get<ApiResponse<{ pharmacyId: string; pharmacyName: string }[]>>(
      '/pharmacy/reconciliation/pharmacies',
    );
  }

  reconciliation(
    pharmacyId: string,
    date: string,
  ): Observable<ApiResponse<PharmacyReconciliation>> {
    const params = new HttpParams().set('pharmacyId', pharmacyId).set('date', date);
    return this.http.get<ApiResponse<PharmacyReconciliation>>('/pharmacy/reconciliation', {
      params,
    });
  }

  reconciliationExport(pharmacyId: string, date: string): Observable<Blob> {
    const params = new HttpParams().set('pharmacyId', pharmacyId).set('date', date);
    return this.http.get('/pharmacy/reconciliation/export', { params, responseType: 'blob' });
  }

  refunds(statuses: PharmacyRefundStatus[] = []): Observable<ApiResponse<PharmacyRefund[]>> {
    let params = new HttpParams();
    for (const s of statuses) params = params.append('status', s);
    return this.http.get<ApiResponse<PharmacyRefund[]>>('/pharmacy/refunds', { params });
  }

  requestRefund(body: PharmacyRefundRequest): Observable<ApiResponse<PharmacyRefund>> {
    return this.http.post<ApiResponse<PharmacyRefund>>('/pharmacy/refunds', body);
  }

  approveRefund(id: string): Observable<ApiResponse<PharmacyRefund>> {
    return this.http.post<ApiResponse<PharmacyRefund>>(`/pharmacy/refunds/${id}/approve`, {});
  }

  rejectRefund(id: string, reason: string): Observable<ApiResponse<PharmacyRefund>> {
    return this.http.post<ApiResponse<PharmacyRefund>>(`/pharmacy/refunds/${id}/reject`, {
      reason,
    });
  }

  payRefund(
    id: string,
    body: { reference?: string; mobileMoneyOperator?: MobileMoneyOperator } = {},
  ): Observable<ApiResponse<PharmacyRefund>> {
    return this.http.post<ApiResponse<PharmacyRefund>>(`/pharmacy/refunds/${id}/pay`, body);
  }

  cancelRefund(id: string, reason: string): Observable<ApiResponse<PharmacyRefund>> {
    return this.http.post<ApiResponse<PharmacyRefund>>(`/pharmacy/refunds/${id}/cancel`, {
      reason,
    });
  }
}
