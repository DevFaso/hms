import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface PatientResponse {
  id: string;
  userId: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber?: string;
  dateOfBirth?: string;
  gender?: string;
  address?: string;
  bloodGroup?: string;
  allergies?: string;
  medicalHistory?: string;
  hospitalId: string;
  hospitalName?: string;
  mrn?: string;
  active: boolean;
  createdAt: string;
}

export interface PatientCreateRequest {
  userId?: string;
  hospitalId: string;
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber?: string;
  dateOfBirth?: string;
  gender?: string;
  address?: string;
  bloodGroup?: string;
}

@Injectable({ providedIn: 'root' })
export class PatientService {
  private readonly http = inject(HttpClient);

  list(hospitalId?: string, search?: string): Observable<PatientResponse[]> {
    let params = new HttpParams();
    if (hospitalId) params = params.set('hospitalId', hospitalId);
    if (search) params = params.set('search', search);
    return this.http.get<PatientResponse[]>('/patients', { params });
  }

  getById(id: string): Observable<PatientResponse> {
    return this.http.get<PatientResponse>(`/patients/${id}`);
  }

  create(req: PatientCreateRequest): Observable<PatientResponse> {
    return this.http.post<PatientResponse>('/patients/staff-register', req);
  }

  update(id: string, req: Partial<PatientCreateRequest>): Observable<PatientResponse> {
    return this.http.put<PatientResponse>(`/patients/${id}`, req);
  }

  lookup(params: { email?: string; phone?: string; mrn?: string }): Observable<PatientResponse[]> {
    let httpParams = new HttpParams();
    if (params.email) httpParams = httpParams.set('email', params.email);
    if (params.phone) httpParams = httpParams.set('phone', params.phone);
    if (params.mrn) httpParams = httpParams.set('mrn', params.mrn);
    return this.http.get<PatientResponse[]>('/patients/lookup', { params: httpParams });
  }
}
