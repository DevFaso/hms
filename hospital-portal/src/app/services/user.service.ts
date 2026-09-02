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
  deleted: boolean;
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
  /** Optional for PATIENT registrations (phone-first); required for staff roles. */
  email?: string;
  password?: string;
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

  /**
   * includeDeleted also returns soft-deleted accounts so the Deleted filter
   * and Restore button have rows to act on. The server honours it only for
   * SUPER_ADMIN / HOSPITAL_ADMIN; other callers silently get the live view.
   */
  list(
    page = 0,
    size = 20,
    includeDeleted = false,
    onlyDeleted = false,
  ): Observable<UserSummaryPage> {
    let params = new HttpParams().set('page', String(page)).set('size', String(size));
    if (includeDeleted) params = params.set('includeDeleted', 'true');
    // onlyDeleted pages the ghost worklist server-side: a mixed page filtered
    // client-side can show "no matches" while deleted users sit on later
    // pages, which is exactly how the invisible-ghost bug looked to admins.
    if (onlyDeleted) params = params.set('onlyDeleted', 'true');
    return this.http.get<UserSummaryPage>('/users', { params });
  }

  /**
   * Server-side search with optional filters. Uses the /users/search endpoint
   * which supports name, role, and email query params with pagination.
   */
  search(
    page = 0,
    size = 20,
    filters: {
      name?: string;
      role?: string;
      email?: string;
      includeDeleted?: boolean;
      onlyDeleted?: boolean;
    } = {},
  ): Observable<UserSummaryPage> {
    let params = new HttpParams().set('page', String(page)).set('size', String(size));
    if (filters.name) params = params.set('name', filters.name);
    if (filters.role) params = params.set('role', filters.role);
    if (filters.email) params = params.set('email', filters.email);
    if (filters.includeDeleted) params = params.set('includeDeleted', 'true');
    if (filters.onlyDeleted) params = params.set('onlyDeleted', 'true');
    return this.http.get<UserSummaryPage>('/users/search', { params });
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

  restore(id: string): Observable<void> {
    return this.http.patch<void>(`/users/${id}/restore`, {});
  }
}
