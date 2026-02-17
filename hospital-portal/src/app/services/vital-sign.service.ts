import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface VitalSignResponse {
  id: string;
  patientId: string;
  staffId: string;
  staffName: string;
  heartRate: number;
  systolicBp: number;
  diastolicBp: number;
  temperature: number;
  respiratoryRate: number;
  oxygenSaturation: number;
  weight: number;
  height: number;
  painLevel: number;
  notes: string;
  recordedAt: string;
  createdAt: string;
}

export interface VitalSignRequest {
  heartRate?: number;
  systolicBp?: number;
  diastolicBp?: number;
  temperature?: number;
  respiratoryRate?: number;
  oxygenSaturation?: number;
  weight?: number;
  height?: number;
  painLevel?: number;
  notes?: string;
}

@Injectable({ providedIn: 'root' })
export class VitalSignService {
  private readonly http = inject(HttpClient);

  record(patientId: string, req: VitalSignRequest): Observable<VitalSignResponse> {
    return this.http.post<VitalSignResponse>(`/patients/${patientId}/vitals`, req);
  }

  getRecent(patientId: string): Observable<VitalSignResponse[]> {
    return this.http.get<VitalSignResponse[]>(`/patients/${patientId}/vitals/recent`);
  }

  list(
    patientId: string,
    params?: { page?: number; size?: number },
  ): Observable<{ content: VitalSignResponse[] }> {
    let httpParams = new HttpParams();
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size) httpParams = httpParams.set('size', params.size);
    return this.http.get<{ content: VitalSignResponse[] }>(`/patients/${patientId}/vitals`, {
      params: httpParams,
    });
  }
}
