import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ── Response DTOs ── */

export interface MfaEnrollmentResponse {
  secret: string;
  otpauthUri: string;
  backupCodes: string[];
}

export interface MfaStatusResponse {
  mfaEnabled: boolean;
}

export interface MfaVerifyLoginResponse {
  tokenType?: string;
  accessToken?: string;
  refreshToken?: string;
  id?: string;
  username?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  phoneNumber?: string;
  profilePictureUrl?: string;
  roles?: string[];
  profileType?: 'STAFF' | 'PATIENT';
  licenseNumber?: string;
  staffId?: string;
  roleName?: string;
  active?: boolean;
  forcePasswordChange?: boolean;
  forceUsernameChange?: boolean;
  primaryHospitalId?: string;
  primaryHospitalName?: string;
  hospitalIds?: string[];
  message?: string;
}

export interface MessageResponse {
  message: string;
}

@Injectable({ providedIn: 'root' })
export class MfaService {
  private readonly http = inject(HttpClient);

  /** Start TOTP enrollment (authenticated). */
  enroll(): Observable<MfaEnrollmentResponse> {
    return this.http.post<MfaEnrollmentResponse>('/auth/mfa/enroll', {});
  }

  /** Verify enrollment with first TOTP code (authenticated). */
  verifyEnrollment(code: string): Observable<MessageResponse> {
    return this.http.post<MessageResponse>('/auth/mfa/verify-enrollment', { code });
  }

  /** Check current user's MFA status (authenticated). */
  getStatus(): Observable<MfaStatusResponse> {
    return this.http.get<MfaStatusResponse>('/auth/mfa/status');
  }

  /** Verify MFA during login flow (unauthenticated — uses mfaToken). */
  verifyLogin(mfaToken: string, code: string): Observable<MfaVerifyLoginResponse> {
    return this.http.post<MfaVerifyLoginResponse>('/auth/mfa/verify', { mfaToken, code });
  }
}
