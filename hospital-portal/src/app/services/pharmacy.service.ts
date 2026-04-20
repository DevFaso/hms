import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ───────────────────────────── Medication Catalog ───────────────────────────── */

export interface MedicationCatalogItemRequest {
  nameFr: string;
  genericName: string;
  brandName?: string;
  atcCode?: string;
  form?: string;
  strength?: string;
  strengthUnit?: string;
  rxnormCode?: string;
  route?: string;
  category?: string;
  essentialList?: boolean;
  controlled?: boolean;
  description?: string;
}

export interface MedicationCatalogItemResponse {
  id: string;
  nameFr: string;
  genericName: string;
  brandName?: string;
  atcCode?: string;
  form?: string;
  strength?: string;
  strengthUnit?: string;
  rxnormCode?: string;
  route?: string;
  category?: string;
  essentialList: boolean;
  controlled: boolean;
  active: boolean;
  description?: string;
  hospitalId?: string;
  hospitalName?: string;
  createdAt: string;
  updatedAt: string;
}

/* ───────────────────────────── Pharmacy Registry ───────────────────────────── */

export interface PharmacyRequest {
  hospitalId: string;
  name: string;
  pharmacyType?: string;
  licenseNumber?: string;
  facilityCode?: string;
  phoneNumber?: string;
  email?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  region?: string;
  postalCode?: string;
  country?: string;
  fulfillmentMode?: string;
  npi?: string;
  ncpdp?: string;
  active?: boolean;
}

export interface PharmacyResponse {
  id: string;
  hospitalId: string;
  name: string;
  pharmacyType?: string;
  licenseNumber?: string;
  facilityCode?: string;
  phoneNumber?: string;
  email?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  region?: string;
  postalCode?: string;
  country?: string;
  fulfillmentMode?: string;
  npi?: string;
  ncpdp?: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

/* ───────────────────────────── Inventory ───────────────────────────── */

export interface InventoryItemRequest {
  pharmacyId: string;
  medicationCatalogItemId: string;
  quantityOnHand: number;
  reorderThreshold: number;
  reorderQuantity: number;
  unit?: string;
  active?: boolean;
}

export interface InventoryItemResponse {
  id: string;
  pharmacyId: string;
  medicationCatalogItemId: string;
  medicationName?: string;
  medicationCode?: string;
  quantityOnHand: number;
  reorderThreshold: number;
  reorderQuantity: number;
  unit?: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface StockLotRequest {
  inventoryItemId: string;
  lotNumber: string;
  expiryDate: string;
  initialQuantity: number;
  remainingQuantity?: number;
  supplier?: string;
  unitCost?: number;
  receivedDate?: string;
  notes?: string;
}

export interface StockLotResponse {
  id: string;
  inventoryItemId: string;
  lotNumber: string;
  expiryDate: string;
  initialQuantity: number;
  remainingQuantity: number;
  supplier?: string;
  unitCost?: number;
  receivedDate?: string;
  receivedBy?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;
}

export interface StockTransactionRequest {
  inventoryItemId: string;
  stockLotId?: string;
  transactionType: string;
  quantity: number;
  reason?: string;
  referenceId?: string;
  performedBy?: string;
}

export interface StockTransactionResponse {
  id: string;
  inventoryItemId: string;
  stockLotId?: string;
  transactionType: string;
  quantity: number;
  reason?: string;
  referenceId?: string;
  performedBy?: string;
  createdAt: string;
}

/* ───────────────────────────── Page wrapper ───────────────────────────── */

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface ApiResponse<T> {
  data: T;
  message?: string;
  status?: string;
}

/* ───────────────────────────── Service ───────────────────────────── */

@Injectable({ providedIn: 'root' })
export class PharmacyService {
  private readonly http = inject(HttpClient);

  // ── Medication Catalog ──

  listMedications(page = 0, size = 20): Observable<Page<MedicationCatalogItemResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<MedicationCatalogItemResponse>>('/medication-catalog', { params });
  }

  searchMedications(
    query: string,
    page = 0,
    size = 20,
  ): Observable<Page<MedicationCatalogItemResponse>> {
    const params = new HttpParams().set('q', query).set('page', page).set('size', size);
    return this.http.get<Page<MedicationCatalogItemResponse>>('/medication-catalog/search', {
      params,
    });
  }

  getMedication(id: string): Observable<MedicationCatalogItemResponse> {
    return this.http.get<MedicationCatalogItemResponse>(`/medication-catalog/${id}`);
  }

  createMedication(req: MedicationCatalogItemRequest): Observable<MedicationCatalogItemResponse> {
    return this.http.post<MedicationCatalogItemResponse>('/medication-catalog', req);
  }

  updateMedication(
    id: string,
    req: MedicationCatalogItemRequest,
  ): Observable<MedicationCatalogItemResponse> {
    return this.http.put<MedicationCatalogItemResponse>(`/medication-catalog/${id}`, req);
  }

  deleteMedication(id: string): Observable<void> {
    return this.http.delete<void>(`/medication-catalog/${id}`);
  }

  // ── Pharmacy Registry ──

  listPharmacies(page = 0, size = 20): Observable<Page<PharmacyResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<PharmacyResponse>>('/pharmacy-registry', { params });
  }

  searchPharmacies(query: string, page = 0, size = 20): Observable<Page<PharmacyResponse>> {
    const params = new HttpParams().set('q', query).set('page', page).set('size', size);
    return this.http.get<Page<PharmacyResponse>>('/pharmacy-registry/search', { params });
  }

  getPharmacy(id: string): Observable<PharmacyResponse> {
    return this.http.get<PharmacyResponse>(`/pharmacy-registry/${id}`);
  }

  createPharmacy(req: PharmacyRequest): Observable<PharmacyResponse> {
    return this.http.post<PharmacyResponse>('/pharmacy-registry', req);
  }

  updatePharmacy(id: string, req: PharmacyRequest): Observable<PharmacyResponse> {
    return this.http.put<PharmacyResponse>(`/pharmacy-registry/${id}`, req);
  }

  deletePharmacy(id: string): Observable<void> {
    return this.http.delete<void>(`/pharmacy-registry/${id}`);
  }

  // ── Inventory Items ──

  listInventoryItems(page = 0, size = 20): Observable<ApiResponse<Page<InventoryItemResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<InventoryItemResponse>>>('/pharmacy/inventory/items', {
      params,
    });
  }

  listInventoryByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<InventoryItemResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<InventoryItemResponse>>>(
      `/pharmacy/inventory/items/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  getInventoryItem(id: string): Observable<ApiResponse<InventoryItemResponse>> {
    return this.http.get<ApiResponse<InventoryItemResponse>>(`/pharmacy/inventory/items/${id}`);
  }

  createInventoryItem(req: InventoryItemRequest): Observable<ApiResponse<InventoryItemResponse>> {
    return this.http.post<ApiResponse<InventoryItemResponse>>('/pharmacy/inventory/items', req);
  }

  updateInventoryItem(
    id: string,
    req: InventoryItemRequest,
  ): Observable<ApiResponse<InventoryItemResponse>> {
    return this.http.put<ApiResponse<InventoryItemResponse>>(
      `/pharmacy/inventory/items/${id}`,
      req,
    );
  }

  deleteInventoryItem(id: string): Observable<ApiResponse<string>> {
    return this.http.delete<ApiResponse<string>>(`/pharmacy/inventory/items/${id}`);
  }

  // ── Stock Lots ──

  receiveStock(req: StockLotRequest): Observable<ApiResponse<StockLotResponse>> {
    return this.http.post<ApiResponse<StockLotResponse>>('/pharmacy/inventory/lots', req);
  }

  getStockLot(id: string): Observable<ApiResponse<StockLotResponse>> {
    return this.http.get<ApiResponse<StockLotResponse>>(`/pharmacy/inventory/lots/${id}`);
  }

  listLotsByInventoryItem(
    inventoryItemId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockLotResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockLotResponse>>>(
      `/pharmacy/inventory/lots/item/${inventoryItemId}`,
      { params },
    );
  }

  listLotsByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockLotResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockLotResponse>>>(
      `/pharmacy/inventory/lots/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  getExpiringSoon(pharmacyId: string, daysAhead = 90): Observable<ApiResponse<StockLotResponse[]>> {
    const params = new HttpParams().set('daysAhead', daysAhead);
    return this.http.get<ApiResponse<StockLotResponse[]>>(
      `/pharmacy/inventory/lots/expiring/${pharmacyId}`,
      { params },
    );
  }

  // ── Reorder Alerts ──

  getReorderAlerts(): Observable<ApiResponse<InventoryItemResponse[]>> {
    return this.http.get<ApiResponse<InventoryItemResponse[]>>(
      '/pharmacy/inventory/reorder-alerts',
    );
  }

  getReorderAlertsByPharmacy(pharmacyId: string): Observable<ApiResponse<InventoryItemResponse[]>> {
    return this.http.get<ApiResponse<InventoryItemResponse[]>>(
      `/pharmacy/inventory/reorder-alerts/pharmacy/${pharmacyId}`,
    );
  }

  triggerReorderAlerts(): Observable<ApiResponse<string>> {
    return this.http.post<ApiResponse<string>>('/pharmacy/inventory/reorder-alerts/trigger', {});
  }

  // ── Stock Transactions ──

  recordTransaction(
    req: StockTransactionRequest,
  ): Observable<ApiResponse<StockTransactionResponse>> {
    return this.http.post<ApiResponse<StockTransactionResponse>>(
      '/pharmacy/stock-transactions',
      req,
    );
  }

  getTransaction(id: string): Observable<ApiResponse<StockTransactionResponse>> {
    return this.http.get<ApiResponse<StockTransactionResponse>>(
      `/pharmacy/stock-transactions/${id}`,
    );
  }

  listTransactionsByItem(
    inventoryItemId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/item/${inventoryItemId}`,
      { params },
    );
  }

  listTransactionsByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  listTransactionsByDateRange(
    pharmacyId: string,
    from: string,
    to: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams()
      .set('from', from)
      .set('to', to)
      .set('page', page)
      .set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/pharmacy/${pharmacyId}/date-range`,
      { params },
    );
  }

  listTransactionsByType(
    pharmacyId: string,
    type: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/pharmacy/${pharmacyId}/type/${type}`,
      { params },
    );
  }
}
