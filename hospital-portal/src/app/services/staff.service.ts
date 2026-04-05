import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { map, Observable } from 'rxjs';

export interface StaffResponse {
  id: string;
  userId: string;
  username: string;
  name: string;
  email: string;
  phoneNumber?: string;
  hospitalId: string;
  hospitalName?: string;
  hospitalEmail?: string;
  departmentId?: string;
  departmentName?: string;
  departmentEmail?: string;
  departmentPhoneNumber?: string;
  roleCode?: string;
  roleName?: string;
  jobTitle?: string;
  employmentType?: string;
  specialization?: string;
  licenseNumber?: string;
  startDate?: string;
  endDate?: string;
  active: boolean;
  headOfDepartment?: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface StaffUpsertRequest {
  userEmail: string;
  hospitalName: string;
  departmentName?: string;
  specialization?: string;
  licenseNumber?: string;
  jobTitle?: string;
  employmentType?: string;
  startDate?: string;
  roleName?: string;
}

export interface LabStaffRoleUpdateRequest {
  roleCode: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class StaffService {
  private readonly http = inject(HttpClient);

  list(hospitalId?: string): Observable<StaffResponse[]> {
    if (hospitalId) {
      // Use the hospital-scoped active endpoint which filters correctly
      return this.http
        .get<{ content: StaffResponse[] }>(`/staff/hospital/${hospitalId}/active`, {
          params: new HttpParams().set('size', '200'),
        })
        .pipe(map((page) => page.content ?? []));
    }
    return this.http.get<StaffResponse[]>('/staff');
  }

  getById(id: string): Observable<StaffResponse> {
    return this.http.get<StaffResponse>(`/staff/${id}/active`);
  }

  create(req: StaffUpsertRequest): Observable<StaffResponse> {
    return this.http.post<StaffResponse>('/staff', req);
  }

  update(id: string, req: Partial<StaffUpsertRequest>): Observable<StaffResponse> {
    return this.http.put<StaffResponse>(`/staff/${id}`, req);
  }

  deactivate(id: string): Observable<void> {
    return this.http.delete<void>(`/staff/${id}`);
  }

  // ── MVP 3: Lab Staff Management ─────────────────────────────

  getLabStaff(hospitalId: string, page = 0, size = 20): Observable<PagedResponse<StaffResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PagedResponse<StaffResponse>>(`/staff/hospital/${hospitalId}/lab`, {
      params,
    });
  }

  updateLabStaffRole(staffId: string, roleCode: string): Observable<StaffResponse> {
    const body: LabStaffRoleUpdateRequest = { roleCode };
    return this.http.put<StaffResponse>(`/staff/${staffId}/lab-role`, body);
  }
}
