import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { Page } from './pharmacy.service';

/**
 * QA D4183 (r81): the physical stock counts, the balance reconciliation and the consumption report of #1344
 * worked through the API and had no screen. This is their client; the endpoints answer the bare object
 * (no `{ data }` wrapper).
 */

/** One of the hospital's own stores (never a partner pharmacy). */
export interface StockStore {
  pharmacyId: string;
  pharmacyName: string;
}

/** PENDING waits for its confirmation; OUTDATED can no longer be applied (the stock moved since). */
export type StockCountStatus = 'PENDING' | 'APPLIED' | 'CLOSED' | 'OUTDATED';

export interface StockCountRequest {
  inventoryItemId: string;
  stockLotId?: string | null;
  /** One id per counted line, kept across a retry: the server records the observation once. */
  clientRequestId: string;
  expectedBookQuantity: number;
  expectedUnit?: string | null;
  expectedItemRevision: number;
  expectedLotRevision?: number | null;
  countedQuantity: number;
  reason: string;
}

export interface StockCountResponse {
  id: string;
  inventoryItemId: string;
  stockLotId?: string | null;
  medicationName?: string | null;
  lotNumber?: string | null;
  unit?: string | null;
  bookQuantity: number;
  countedQuantity: number;
  variance: number;
  reason: string;
  recordedByName?: string | null;
  recordedAt: string;
  appliedAt?: string | null;
  appliedByName?: string | null;
  adjustmentId?: string | null;
  samePersonConfirmation?: boolean | null;
  status: StockCountStatus;
  closedAt?: string | null;
  closedByName?: string | null;
  closeReason?: string | null;
  /** The reader recorded this count: a colleague confirms it when one exists. */
  ownCount: boolean;
  /** A controlled substance: the adjustment of a variance needs a witness. */
  controlled: boolean;
}

export interface StockBalanceDriftLot {
  lotId: string;
  lotNumber: string;
  expiryDate?: string | null;
  remainingQuantity: number;
}

/** A stock item whose total on hand no longer equals the sum of its lots. */
export interface StockBalanceDrift {
  inventoryItemId: string;
  medicationName?: string | null;
  strength?: string | null;
  form?: string | null;
  unit?: string | null;
  controlled: boolean;
  quantityOnHand: number;
  lotQuantity: number;
  difference: number;
  stockRevision: number;
  lots: StockBalanceDriftLot[];
}

export interface StockBalanceReconciliationRequest {
  expectedOnHand: number;
  expectedLotQuantity: number;
  expectedItemRevision: number;
  confirmed: boolean;
  reason: string;
  witnessedBy?: string | null;
  lotCounts: { lotId: string; countedQuantity: number }[];
}

export interface StockConsumptionLine {
  inventoryItemId: string;
  medicationName?: string | null;
  strength?: string | null;
  form?: string | null;
  /** Null for the movements recorded before the unit was kept with each movement. */
  unit?: string | null;
  issuedQuantity: number | null;
  returnedQuantity: number | null;
  unitHistoryComparable: boolean;
  sourceMovements: number;
}

export interface StockConsumptionReport {
  from: string;
  to: string;
  items: StockConsumptionLine[];
}

@Injectable({ providedIn: 'root' })
export class StockCountService {
  private readonly http = inject(HttpClient);
  private readonly base = '/pharmacy/stock-counts';

  stores(): Observable<StockStore[]> {
    return this.http.get<StockStore[]>(`${this.base}/stores`);
  }

  list(pharmacyId: string, page = 0, size = 50): Observable<Page<StockCountResponse>> {
    const params = new HttpParams()
      .set('pharmacyId', pharmacyId)
      .set('page', page)
      .set('size', size);
    return this.http.get<Page<StockCountResponse>>(this.base, { params });
  }

  record(request: StockCountRequest): Observable<StockCountResponse> {
    return this.http.post<StockCountResponse>(this.base, request);
  }

  confirm(id: string, witnessedBy?: string | null): Observable<StockCountResponse> {
    return this.http.post<StockCountResponse>(`${this.base}/${id}/confirm-adjustment`, {
      confirmed: true,
      witnessedBy: witnessedBy || null,
    });
  }

  close(id: string, reason: string): Observable<StockCountResponse> {
    return this.http.post<StockCountResponse>(`${this.base}/${id}/close`, { reason });
  }

  balanceDrift(pharmacyId: string): Observable<StockBalanceDrift[]> {
    const params = new HttpParams().set('pharmacyId', pharmacyId);
    return this.http.get<StockBalanceDrift[]>(`${this.base}/balance-drift`, { params });
  }

  reconcileBalance(
    inventoryItemId: string,
    request: StockBalanceReconciliationRequest,
  ): Observable<string> {
    return this.http.post<string>(
      `${this.base}/items/${inventoryItemId}/reconcile-balance`,
      request,
    );
  }

  consumption(pharmacyId: string, from: string, to: string): Observable<StockConsumptionReport> {
    const params = new HttpParams().set('pharmacyId', pharmacyId).set('from', from).set('to', to);
    return this.http.get<StockConsumptionReport>(`${this.base}/consumption`, { params });
  }
}
