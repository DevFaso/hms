import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PagedResponse } from './staff.service';

// ── Instrument interfaces ────────────────────────────────────

export interface LabInstrumentRequest {
  name: string;
  manufacturer?: string;
  modelNumber?: string;
  serialNumber: string;
  departmentId?: string;
  status?: string;
  installationDate?: string;
  lastCalibrationDate?: string;
  nextCalibrationDate?: string;
  lastMaintenanceDate?: string;
  nextMaintenanceDate?: string;
  notes?: string;
}

export interface LabInstrumentResponse {
  id: string;
  name: string;
  manufacturer?: string;
  modelNumber?: string;
  serialNumber: string;
  hospitalId: string;
  hospitalName?: string;
  departmentId?: string;
  departmentName?: string;
  status: string;
  installationDate?: string;
  lastCalibrationDate?: string;
  nextCalibrationDate?: string;
  lastMaintenanceDate?: string;
  nextMaintenanceDate?: string;
  maintenanceOverdue: boolean;
  calibrationOverdue: boolean;
  /** False once deactivated (QA D4314). */
  active?: boolean;
  notes?: string;
  createdAt: string;
  updatedAt?: string;
}

// ── Inventory interfaces ─────────────────────────────────────

export interface LabInventoryItemRequest {
  name: string;
  itemCode: string;
  category?: string;
  quantity: number;
  unit?: string;
  reorderThreshold: number;
  supplier?: string;
  lotNumber?: string;
  expirationDate?: string;
  notes?: string;
}

export interface LabInventoryItemResponse {
  id: string;
  name: string;
  itemCode: string;
  category?: string;
  hospitalId: string;
  hospitalName?: string;
  quantity: number;
  unit?: string;
  reorderThreshold: number;
  supplier?: string;
  lotNumber?: string;
  expirationDate?: string;
  lowStock: boolean;
  expired: boolean;
  notes?: string;
  createdAt: string;
  updatedAt?: string;
}

// ── Service ──────────────────────────────────────────────────

@Injectable({ providedIn: 'root' })
export class LabInstrumentService {
  private readonly http = inject(HttpClient);

  // ── Instruments ────────────────────────────────────────────

  getInstruments(
    hospitalId: string,
    page = 0,
    size = 20,
    includeInactive = false,
  ): Observable<PagedResponse<LabInstrumentResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    // QA D4314: deactivated instruments are listed on request, so they can be reactivated.
    if (includeInactive) params = params.set('includeInactive', 'true');
    return this.http.get<PagedResponse<LabInstrumentResponse>>(
      `/lab/instruments/hospital/${hospitalId}`,
      { params },
    );
  }

  getInstrument(id: string): Observable<LabInstrumentResponse> {
    return this.http.get<LabInstrumentResponse>(`/lab/instruments/${id}`);
  }

  createInstrument(
    hospitalId: string,
    request: LabInstrumentRequest,
  ): Observable<LabInstrumentResponse> {
    return this.http.post<LabInstrumentResponse>(
      `/lab/instruments/hospital/${hospitalId}`,
      request,
    );
  }

  updateInstrument(id: string, request: LabInstrumentRequest): Observable<LabInstrumentResponse> {
    return this.http.put<LabInstrumentResponse>(`/lab/instruments/${id}`, request);
  }

  /** QA D4314: puts a deactivated instrument back in the list. */
  reactivateInstrument(id: string): Observable<LabInstrumentResponse> {
    return this.http.post<LabInstrumentResponse>(`/lab/instruments/${id}/reactivate`, {});
  }

  deactivateInstrument(id: string): Observable<void> {
    return this.http.delete<void>(`/lab/instruments/${id}`);
  }

  // ── Inventory ──────────────────────────────────────────────

  getInventoryItems(
    hospitalId: string,
    page = 0,
    size = 20,
    search = '',
  ): Observable<PagedResponse<LabInventoryItemResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    const url = `/lab/inventory/hospital/${hospitalId}`;
    const query = search.trim();
    return query
      ? this.http.post<PagedResponse<LabInventoryItemResponse>>(
          `${url}/search`,
          { query },
          { params },
        )
      : this.http.get<PagedResponse<LabInventoryItemResponse>>(url, { params });
  }

  getInventoryItem(id: string): Observable<LabInventoryItemResponse> {
    return this.http.get<LabInventoryItemResponse>(`/lab/inventory/${id}`);
  }

  createInventoryItem(
    hospitalId: string,
    request: LabInventoryItemRequest,
  ): Observable<LabInventoryItemResponse> {
    return this.http.post<LabInventoryItemResponse>(
      `/lab/inventory/hospital/${hospitalId}`,
      request,
    );
  }

  updateInventoryItem(
    id: string,
    request: LabInventoryItemRequest,
  ): Observable<LabInventoryItemResponse> {
    return this.http.put<LabInventoryItemResponse>(`/lab/inventory/${id}`, request);
  }

  deactivateInventoryItem(id: string): Observable<void> {
    return this.http.delete<void>(`/lab/inventory/${id}`);
  }

  getLowStockItems(hospitalId: string): Observable<LabInventoryItemResponse[]> {
    return this.http.get<LabInventoryItemResponse[]>(
      `/lab/inventory/hospital/${hospitalId}/low-stock`,
    );
  }
}
