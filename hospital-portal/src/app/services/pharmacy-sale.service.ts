import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MobileMoneyOperator } from '../shared/mobile-money-operator';
import { Page } from './pharmacy.service';

/**
 * QA D2309: the counter (OTC, walk-in) sale of the pharmacy, `POST /pharmacy-sales`.
 *
 * Only the till methods: an insurer's share is not money in the drawer (QA D1611,
 * the server's TillPaymentMethods refuses INSURANCE).
 */
export type CounterSaleMethod = 'CASH' | 'MOBILE_MONEY';

/** One line of a counter sale. The server prices it from the list price: no `unitPrice` is sent. */
export interface CounterSaleLineRequest {
  medicationCatalogItemId: string;
  /** Required when the pharmacy keeps the item in lots (QA D2670). */
  stockLotId?: string;
  quantity: number;
  /** QA D2674: `quantity` counts whole packs of an item with `unitsPerPack`. */
  inPacks?: boolean;
  /** QA D964: why a later lot was taken while a shorter-dated one still holds stock. */
  fefoOverrideReason?: string;
}

export interface CounterSaleRequest {
  pharmacyId: string;
  hospitalId: string;
  /** Absent: an anonymous walk-in. */
  patientId?: string;
  paymentMethod: CounterSaleMethod;
  currency?: string;
  /** Non-cash only; never a card number (owner decision D-15, the server's NoCardNumber). */
  referenceNumber?: string;
  /** Required for MOBILE_MONEY (billing plan PR 9). */
  mobileMoneyOperator?: MobileMoneyOperator;
  notes?: string;
  /** QA D2430: one key per open sale form, sent again on a retry. */
  clientRequestId?: string;
  lines: CounterSaleLineRequest[];
}

export interface CounterSaleLineResponse {
  id: string;
  medicationCatalogItemId: string;
  medicationName?: string | null;
  stockLotId?: string | null;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  notes?: string | null;
}

export interface CounterSaleResponse {
  id: string;
  /** QA D4186 (r81): the readable receipt number; absent on a sale recorded before it was allocated. */
  receiptNumber?: string | null;
  pharmacyId: string;
  pharmacyName?: string | null;
  hospitalId: string;
  patientId?: string | null;
  soldByUserId?: string | null;
  saleDate: string;
  paymentMethod: string;
  totalAmount: number;
  currency?: string | null;
  referenceNumber?: string | null;
  mobileMoneyOperator?: string | null;
  status?: string | null;
  notes?: string | null;
  lines?: CounterSaleLineResponse[];
  createdAt?: string;
  updatedAt?: string;
}

@Injectable({ providedIn: 'root' })
export class PharmacySaleService {
  private readonly http = inject(HttpClient);

  /** QA D2309: records a counter sale; it lands in the pharmacist's PHARMACY till. */
  createSale(req: CounterSaleRequest): Observable<CounterSaleResponse> {
    return this.http.post<CounterSaleResponse>('/pharmacy-sales', req);
  }

  /** One page of the sales recorded at a pharmacy, in the order `sort` asks. */
  listByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
    sort: readonly string[] = [],
  ): Observable<Page<CounterSaleResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const s of sort) params = params.append('sort', s);
    return this.http.get<Page<CounterSaleResponse>>(`/pharmacy-sales/by-pharmacy/${pharmacyId}`, {
      params,
    });
  }
}
