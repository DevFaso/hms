import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface UserMfaEnrollmentDTO {
  method: string;
  channel: string;
  enabled: boolean;
  primaryFactor: boolean;
  enrolledAt: string;
  lastVerifiedAt: string;
}

export interface UserRecoveryContactDTO {
  id: string;
  contactType: string;
  contactValue: string;
  verified: boolean;
  verifiedAt: string;
  primaryContact: boolean;
  notes: string;
}

export interface UserCredentialHealthDTO {
  userId: string;
  username: string;
  email: string;
  active: boolean;
  forcePasswordChange: boolean;
  forceUsernameChange: boolean;
  lastLoginAt: string;
  mfaEnrolledCount: number;
  verifiedMfaCount: number;
  hasPrimaryMfa: boolean;
  recoveryContactCount: number;
  verifiedRecoveryContacts: number;
  hasPrimaryRecoveryContact: boolean;
  mfaEnrollments: UserMfaEnrollmentDTO[];
  recoveryContacts: UserRecoveryContactDTO[];
}

@Injectable({ providedIn: 'root' })
export class CredentialHealthService {
  private readonly http = inject(HttpClient);

  listCredentialHealth(): Observable<UserCredentialHealthDTO[]> {
    return this.http.get<UserCredentialHealthDTO[]>('/super-admin/credentials/health');
  }

  getCredentialHealth(userId: string): Observable<UserCredentialHealthDTO> {
    return this.http.get<UserCredentialHealthDTO>(`/super-admin/credentials/health/${userId}`);
  }

  upsertMfaEnrollments(
    userId: string,
    payload: Partial<UserMfaEnrollmentDTO>[],
  ): Observable<UserMfaEnrollmentDTO[]> {
    return this.http.put<UserMfaEnrollmentDTO[]>(`/super-admin/credentials/${userId}/mfa`, payload);
  }

  upsertRecoveryContacts(
    userId: string,
    payload: Partial<UserRecoveryContactDTO>[],
  ): Observable<UserRecoveryContactDTO[]> {
    return this.http.put<UserRecoveryContactDTO[]>(
      `/super-admin/credentials/${userId}/recovery`,
      payload,
    );
  }
}
