import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ── Interfaces ── */

export interface RoleSummary {
  id: string;
  code: string;
  name: string;
  description?: string;
}

export interface UserProfile {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber?: string;
  profileImageUrl?: string;
  active: boolean;
  lastLoginAt?: string;
  createdAt?: string;
  updatedAt?: string;
  roles: RoleSummary[];
  profileType?: string;
  licenseNumber?: string;
  roleName?: string;
  patientId?: string;
  staffId?: string;
  roleCount?: number;
  forcePasswordChange?: boolean;
}

export interface CredentialHealth {
  userId: string;
  username: string;
  email: string;
  active: boolean;
  forcePasswordChange: boolean;
  lastLoginAt?: string;
  mfaEnrolledCount: number;
  verifiedMfaCount: number;
  hasPrimaryMfa: boolean;
  recoveryContactCount: number;
  verifiedRecoveryContacts: number;
  hasPrimaryRecoveryContact: boolean;
  mfaEnrollments: MfaEnrollment[];
  recoveryContacts: RecoveryContact[];
}

export interface MfaEnrollment {
  id?: string;
  method: string;
  destination?: string;
  verified: boolean;
  primary: boolean;
  createdAt?: string;
}

export interface RecoveryContact {
  id?: string;
  contactType: string;
  contactValue: string;
  verified: boolean;
  primary: boolean;
  createdAt?: string;
}

export interface Assignment {
  id: string;
  hospitalId?: string;
  hospitalName?: string;
  roleId?: string;
  roleName?: string;
  roleCode?: string;
  active: boolean;
}

export interface ProfileUpdateRequest {
  firstName?: string;
  lastName?: string;
  email?: string;
  phoneNumber?: string;
  username?: string;
  password?: string;
}

export interface AuditEvent {
  userName: string;
  hospitalName: string;
  roleName: string;
  eventType: string;
  eventDescription: string;
  details: string;
  eventTimestamp: string;
  ipAddress: string;
  status: string;
  resourceId: string;
  resourceName: string;
  entityType: string;
}

@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly http = inject(HttpClient);

  /** GET /users/:id  — full user profile */
  getUserProfile(userId: string): Observable<UserProfile> {
    return this.http.get<UserProfile>(`/users/${userId}`);
  }

  /** PUT /users/:id  — update user profile */
  updateProfile(userId: string, data: ProfileUpdateRequest): Observable<UserProfile> {
    return this.http.put<UserProfile>(`/users/${userId}`, data);
  }

  /** GET /auth/credentials/me — credential health (MFA, recovery, password status) */
  getCredentialHealth(): Observable<CredentialHealth> {
    return this.http.get<CredentialHealth>('/auth/credentials/me');
  }

  /** GET /me/assignments — role assignments for authenticated user */
  getAssignments(): Observable<Assignment[]> {
    return this.http.get<Assignment[]>('/me/assignments');
  }

  /** POST /files/profile-image — upload avatar */
  uploadAvatar(file: File): Observable<{ message: string; imageUrl: string }> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<{ message: string; imageUrl: string }>('/files/profile-image', form);
  }

  /** DELETE /files/profile-image — remove avatar */
  deleteAvatar(): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>('/files/profile-image');
  }

  /** GET /audit-logs/user/:id — recent activity for a user */
  getActivityLog(userId: string, page = 0, size = 20): Observable<{ content: AuditEvent[] }> {
    return this.http.get<{ content: AuditEvent[] }>(`/audit-logs/user/${userId}`, {
      params: { page: String(page), size: String(size) },
    });
  }

  /** POST /auth/password/request — request password reset email */
  requestPasswordReset(email: string): Observable<void> {
    return this.http.post<void>('/auth/password/request', { email });
  }

  /** PUT /auth/credentials/mfa — update MFA enrollments */
  updateMfaEnrollments(
    enrollments: { method: string; destination?: string; primary?: boolean }[],
  ): Observable<MfaEnrollment[]> {
    return this.http.put<MfaEnrollment[]>('/auth/credentials/mfa', enrollments);
  }

  /** PUT /auth/credentials/recovery — update recovery contacts */
  updateRecoveryContacts(
    contacts: { contactType: string; contactValue: string; primary?: boolean }[],
  ): Observable<RecoveryContact[]> {
    return this.http.put<RecoveryContact[]>('/auth/credentials/recovery', contacts);
  }
}
