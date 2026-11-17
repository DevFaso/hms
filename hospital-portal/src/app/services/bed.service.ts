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

  private hospitalOptions(hospitalId?: string): { headers?: Record<string, string> } {
    return hospitalId ? { headers: { 'X-Hospital-Id': hospitalId } } : {};
  }

  getWards(includeInactive = false): Observable<WardResponse[]> {
    let params = new HttpParams();
    if (includeInactive) params = params.set('includeInactive', true);
    return this.http.get<WardResponse[]>('/wards', { params });
  }

  createWard(data: WardRequest, hospitalId?: string): Observable<WardResponse> {
    return this.http.post<WardResponse>('/wards', data, this.hospitalOptions(hospitalId));
  }

  updateWard(wardId: string, data: WardRequest, hospitalId?: string): Observable<WardResponse> {
    return this.http.put<WardResponse>(`/wards/${wardId}`, data, this.hospitalOptions(hospitalId));
  }

  deleteWard(wardId: string, hospitalId?: string): Observable<void> {
    return this.http.delete<void>(`/wards/${wardId}`, this.hospitalOptions(hospitalId));
  }

  getBeds(wardId: string): Observable<BedResponse[]> {
    return this.http.get<BedResponse[]>(`/wards/${wardId}/beds`);
  }

  /** QA D609: with a patient, the beds of a ward they cannot take are left out. */
  getAvailableBeds(patientId?: string): Observable<BedResponse[]> {
    return patientId
      ? this.http.get<BedResponse[]>('/beds/available', { params: { patientId } })
      : this.http.get<BedResponse[]>('/beds/available');
  }

  createBed(wardId: string, data: BedRequest, hospitalId?: string): Observable<BedResponse> {
    return this.http.post<BedResponse>(
      `/wards/${wardId}/beds`,
      data,
      this.hospitalOptions(hospitalId),
    );
  }

  updateBed(bedId: string, data: BedRequest, hospitalId?: string): Observable<BedResponse> {
    return this.http.put<BedResponse>(`/beds/${bedId}`, data, this.hospitalOptions(hospitalId));
  }

  updateBedStatus(
    bedId: string,
    status: BedStatus,
    notes?: string,
    hospitalId?: string,
  ): Observable<BedResponse> {
    return this.http.patch<BedResponse>(
      `/beds/${bedId}/status`,
      { status, notes },
      this.hospitalOptions(hospitalId),
    );
  }

  deleteBed(bedId: string, hospitalId?: string): Observable<void> {
    return this.http.delete<void>(`/beds/${bedId}`, this.hospitalOptions(hospitalId));
  }
}
