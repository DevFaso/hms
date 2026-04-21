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

/* ───────────────────────────── Dispensing ───────────────────────────── */

export interface DispenseRequest {
  prescriptionId: string;
  patientId: string;
  pharmacyId: string;
  dispensedBy: string;
  verifiedBy?: string;
  medicationCatalogItemId?: string;
  stockLotId?: string;
  medicationName: string;
  quantityRequested: number;
  quantityDispensed: number;
  unit?: string;
  substitution?: boolean;
  substitutionReason?: string;
  notes?: string;
}

export interface DispenseResponse {
  id: string;
  prescriptionId: string;
  patientId: string;
  patientName?: string;
  pharmacyId: string;
  pharmacyName?: string;
  stockLotId?: string;
  dispensedById: string;
  dispensedByName?: string;
  verifiedById?: string;
  verifiedByName?: string;
  medicationCatalogItemId?: string;
  medicationName: string;
  quantityRequested: number;
  quantityDispensed: number;
  unit?: string;
  substitution: boolean;
  substitutionReason?: string;
  status: string;
  notes?: string;
  dispensedAt: string;
  createdAt: string;
  updatedAt: string;
}

/** Work-queue prescription — minimal projection returned by GET /pharmacy/dispense/work-queue. */
export interface WorkQueuePrescription {
  id: string;
  medicationName: string;
  dosage?: string;
  frequency?: string;
  quantity?: number;
  quantityUnit?: string;
  status: string;
  createdAt?: string;
  patient?: {
    id: string;
    firstName?: string;
    lastName?: string;
  };
  staff?: {
    id: string;
    user?: {
      id: string;
      firstName?: string;
      lastName?: string;
    };
  };
}

/* ───────────────────────────── Stock-Out Routing ───────────────────────────── */

export interface StockCheckResult {
  medicationName: string;
  /** Null when `quantityOnHand` is an aggregate across all hospital dispensaries. */
  pharmacyName: string | null;
  /** Null when `quantityOnHand` is an aggregate across all hospital dispensaries. */
  pharmacyId: string | null;
  quantityOnHand: number;
  sufficient: boolean;
  partnerPharmacies: PartnerOption[];
}

export interface PartnerOption {
  pharmacyId: string;
  pharmacyName: string;
  pharmacyType: string;
  city: string;
  phoneNumber: string;
  hasOnFormulary: boolean;
}

export interface RoutingDecisionRequest {
  prescriptionId: string;
  routingType: string;
  targetPharmacyId?: string;
  reason?: string;
  estimatedRestockDate?: string;
}

export interface RoutingDecisionResponse {
  id: string;
  prescriptionId: string;
  routingType: string;
  targetPharmacyId?: string;
  targetPharmacyName?: string;
  decidedByUserId: string;
  patientId: string;
  reason?: string;
  estimatedRestockDate?: string;
  status: string;
  decidedAt: string;
  createdAt: string;
  updatedAt: string;
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

  // ── Dispensing ──

  getDispenseWorkQueue(page = 0, size = 20): Observable<ApiResponse<Page<WorkQueuePrescription>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<WorkQueuePrescription>>>(
      '/pharmacy/dispense/work-queue',
      { params },
    );
  }

  createDispense(req: DispenseRequest): Observable<ApiResponse<DispenseResponse>> {
    return this.http.post<ApiResponse<DispenseResponse>>('/pharmacy/dispense', req);
  }

  getDispense(id: string): Observable<ApiResponse<DispenseResponse>> {
    return this.http.get<ApiResponse<DispenseResponse>>(`/pharmacy/dispense/${id}`);
  }

  listDispensesByPrescription(
    prescriptionId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<DispenseResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<DispenseResponse>>>(
      `/pharmacy/dispense/prescription/${prescriptionId}`,
      { params },
    );
  }

  listDispensesByPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<DispenseResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<DispenseResponse>>>(
      `/pharmacy/dispense/patient/${patientId}`,
      { params },
    );
  }

  listDispensesByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<DispenseResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<DispenseResponse>>>(
      `/pharmacy/dispense/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  cancelDispense(id: string): Observable<ApiResponse<DispenseResponse>> {
    return this.http.post<ApiResponse<DispenseResponse>>(`/pharmacy/dispense/${id}/cancel`, {});
  }

  // ── Stock-Out Routing ──

  checkStock(prescriptionId: string): Observable<ApiResponse<StockCheckResult>> {
    return this.http.get<ApiResponse<StockCheckResult>>(
      `/pharmacy/routing/stock-check/${prescriptionId}`,
    );
  }

  routeToPartner(req: RoutingDecisionRequest): Observable<ApiResponse<RoutingDecisionResponse>> {
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      '/pharmacy/routing/route-to-partner',
      req,
    );
  }

  printForPatient(prescriptionId: string): Observable<ApiResponse<RoutingDecisionResponse>> {
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/print-for-patient/${prescriptionId}`,
      {},
    );
  }

  backOrder(
    prescriptionId: string,
    estimatedRestockDate?: string,
  ): Observable<ApiResponse<RoutingDecisionResponse>> {
    let params = new HttpParams();
    if (estimatedRestockDate) {
      params = params.set('estimatedRestockDate', estimatedRestockDate);
    }
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/back-order/${prescriptionId}`,
      {},
      { params },
    );
  }

  partnerRespond(
    routingDecisionId: string,
    accepted: boolean,
  ): Observable<ApiResponse<RoutingDecisionResponse>> {
    const params = new HttpParams().set('accepted', accepted);
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/partner-respond/${routingDecisionId}`,
      {},
      { params },
    );
  }

  confirmPartnerDispense(
    routingDecisionId: string,
  ): Observable<ApiResponse<RoutingDecisionResponse>> {
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/partner-dispense-confirm/${routingDecisionId}`,
      {},
    );
  }

  listRoutingDecisionsByPrescription(
    prescriptionId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<RoutingDecisionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<RoutingDecisionResponse>>>(
      `/pharmacy/routing/decisions/prescription/${prescriptionId}`,
      { params },
    );
  }

  listRoutingDecisionsByPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<RoutingDecisionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<RoutingDecisionResponse>>>(
      `/pharmacy/routing/decisions/patient/${patientId}`,
      { params },
    );
  }
}
