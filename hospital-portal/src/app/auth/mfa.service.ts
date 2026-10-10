import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/* ── Response DTOs ── */

export interface MfaEnrollmentResponse {
  secret: string;
  otpauthUri: string;
  qrCodeDataUrl: string;
  backupCodes: string[];
}

/** The reason codes of a refused MFA step-up (403), mapped to profile messages. */
export const MFA_STEP_UP_MESSAGES: Readonly<Record<string, string>> = {
  'mfa.stepup.required': 'PROFILE.MFA_STEPUP_REQUIRED',
  'mfa.stepup.invalid': 'PROFILE.MFA_STEPUP_INVALID',
  'mfa.stepup.challenge': 'PROFILE.MFA_STEPUP_CHALLENGE',
  'mfa.stepup.locked': 'PROFILE.MFA_STEPUP_LOCKED',
};

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

  /**
   * Start TOTP enrollment (authenticated). With an active authenticator this
   * starts its replacement, which needs `stepUpCode`: a current code from it,
   * or an unused backup code. The current one keeps working until the new one
   * is confirmed with verifyEnrollment.
   */
  enroll(stepUpCode?: string): Observable<MfaEnrollmentResponse> {
    const options = stepUpCode ? { headers: new HttpHeaders({ 'X-Mfa-Token': stepUpCode }) } : {};
    return this.http.post<MfaEnrollmentResponse>('/auth/mfa/enroll', {}, options);
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
