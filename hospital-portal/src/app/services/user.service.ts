import type { PatientCreateRequest } from './patient.service';
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

/**
 * QA D790: one colleague in the chat's new-conversation picker
 * (`GET /users/chat-recipients`): id, name and role, nothing else.
 */
export interface ChatRecipient {
  id: string;
  firstName?: string | null;
  lastName?: string | null;
  roleName?: string | null;
  profileType?: string | null;
}

export interface UserSummaryPage {
  content: UserSummary[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

import { NotificationDeliveryStatus } from '../shared/delivery-warnings';

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
  /** Present on admin-register responses: did the activation message go anywhere? */
  activationDelivery?: NotificationDeliveryStatus[];
  /**
   * Server-generated one-time credentials, present on an admin-register
   * response ONLY when no e-mail or SMS could carry them. Show once, never store.
   */
  temporaryUsername?: string;
  temporaryPassword?: string;
  /** Registration responses only: true when this call created the account. */
  newAccount?: boolean;
  /** Patient registrations only: the patient record created with the account (QA D626). */
  patientId?: string;
}

export interface AdminRegisterRequest {
  /** Optional on create: the server generates a temporary one (forceUsernameChange). */
  username?: string;
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
  /** QA D626: true only from the patient form; staff onboarding never registers a patient. */
  patientRegistration?: boolean;
  /** QA D626: required with patientRegistration; written with the account in one transaction. */
  patient?: PatientCreateRequest;
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

  /**
   * QA D790: `GET /users` is the administrators' directory; the chat picker
   * reads the staff-wide, minimal colleague list instead.
   */
  chatRecipients(size = 200): Observable<ChatRecipient[]> {
    const params = new HttpParams().set('size', String(size));
    return this.http.get<ChatRecipient[]>('/users/chat-recipients', { params });
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
