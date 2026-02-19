import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface UserSummary {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  profileImageUrl?: string;
  active: boolean;
  roleName: string;
  profileType: string;
  roleCount: number;
}

export interface UserSummaryPage {
  content: UserSummary[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface UserDetail {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber?: string;
  profileImageUrl?: string;
  active: boolean;
  roles: string[];
  createdAt: string;
  updatedAt: string;
}

export interface AdminRegisterRequest {
  username: string;
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  phoneNumber: string;
  roleNames: string[];
  hospitalId?: string;
  hospitalName?: string;
  licenseNumber?: string;
  jobTitle?: string;
  employmentType?: string;
  departmentId?: string;
  specialization?: string;
  forcePasswordChange?: boolean;
}

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);

  list(page = 0, size = 20): Observable<UserSummaryPage> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<UserSummaryPage>('/users', { params });
  }

  getById(id: string): Observable<UserDetail> {
    return this.http.get<UserDetail>(`/users/${id}`);
  }

  adminRegister(req: AdminRegisterRequest): Observable<UserDetail> {
    return this.http.post<UserDetail>('/users/admin-register', req);
  }

  update(id: string, req: Partial<AdminRegisterRequest>): Observable<UserDetail> {
    return this.http.put<UserDetail>(`/users/${id}`, req);
  }

  delete(id: string): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>(`/users/${id}`);
  }
}
