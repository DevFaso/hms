import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type WardType =
  | 'GENERAL'
  | 'SURGICAL'
  | 'MATERNITY'
  | 'PEDIATRIC'
  | 'ICU'
  | 'CCU'
  | 'NICU'
  | 'PSYCHIATRIC'
  | 'ISOLATION'
  | 'PRIVATE'
  | 'SEMI_PRIVATE'
  | 'EMERGENCY'
  | 'RECOVERY';

export type BedStatus = 'AVAILABLE' | 'OCCUPIED' | 'RESERVED' | 'MAINTENANCE' | 'OUT_OF_SERVICE';

export interface WardResponse {
  id: string;
  hospitalId: string;
  name: string;
  code: string;
  wardType: WardType;
  floor: number | null;
  description: string | null;
  departmentId: string | null;
  departmentName: string | null;
  active: boolean;
  totalBeds: number;
  availableBeds: number;
  occupiedBeds: number;
}

export interface WardRequest {
  name: string;
  code: string;
  wardType: WardType;
  floor?: number | null;
  description?: string | null;
  departmentId?: string | null;
  active?: boolean;
}

export interface BedResponse {
  id: string;
  wardId: string;
  wardName: string;
  wardCode: string;
  bedNumber: string;
  /** e.g. "MAT01/B03" — same shape as Admission.roomBed. */
  label: string;
  status: BedStatus;
  bedType: string | null;
  floor: number | null;
  roomNumber: string | null;
  notes: string | null;
  active: boolean;
}

export interface BedRequest {
  bedNumber: string;
  bedType?: string | null;
  floor?: number | null;
  roomNumber?: string | null;
  notes?: string | null;
  active?: boolean;
}

@Injectable({ providedIn: 'root' })
export class BedService {
  private readonly http = inject(HttpClient);

  getWards(includeInactive = false): Observable<WardResponse[]> {
    let params = new HttpParams();
    if (includeInactive) params = params.set('includeInactive', true);
    return this.http.get<WardResponse[]>('/wards', { params });
  }

  createWard(data: WardRequest): Observable<WardResponse> {
    return this.http.post<WardResponse>('/wards', data);
  }

  updateWard(wardId: string, data: WardRequest): Observable<WardResponse> {
    return this.http.put<WardResponse>(`/wards/${wardId}`, data);
  }

  deleteWard(wardId: string): Observable<void> {
    return this.http.delete<void>(`/wards/${wardId}`);
  }

  getBeds(wardId: string): Observable<BedResponse[]> {
    return this.http.get<BedResponse[]>(`/wards/${wardId}/beds`);
  }

  getAvailableBeds(): Observable<BedResponse[]> {
    return this.http.get<BedResponse[]>('/beds/available');
  }

  createBed(wardId: string, data: BedRequest): Observable<BedResponse> {
    return this.http.post<BedResponse>(`/wards/${wardId}/beds`, data);
  }

  updateBed(bedId: string, data: BedRequest): Observable<BedResponse> {
    return this.http.put<BedResponse>(`/beds/${bedId}`, data);
  }

  updateBedStatus(bedId: string, status: BedStatus, notes?: string): Observable<BedResponse> {
    return this.http.patch<BedResponse>(`/beds/${bedId}/status`, { status, notes });
  }

  deleteBed(bedId: string): Observable<void> {
    return this.http.delete<void>(`/beds/${bedId}`);
  }
}
