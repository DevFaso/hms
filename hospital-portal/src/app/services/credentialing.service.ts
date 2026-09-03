import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * One recorded credential renewal (Tier 2 item 40).
 *
 * `previousExpiryDate` is null when the practitioner had no expiry on file —
 * a first recording rather than a renewal. Worth distinguishing in the UI:
 * the two mean quite different things about how long this licence has gone
 * unchecked.
 */
export interface CredentialRenewal {
  id: string;
  staffId: string;
  previousLicenseNumber: string | null;
  previousExpiryDate: string | null;
  licenseNumber: string | null;
  expiryDate: string | null;
  issuingAuthority: string | null;
  note: string | null;
  recordedByUserId: string | null;
  recordedByName: string | null;
  recordedAt: string;
}

export interface CredentialRenewalRequest {
  /**
   * Always sent, and applied by the server exactly as sent.
   *
   * <p>A date sets the licence expiry to it. Null records a qualification
   * that does not expire — a diploma, which is how clinicians are
   * credentialed here — and clears any expiry already on file, taking the
   * practitioner out of the expiry sweep. Null is a statement, not an
   * omission: there is no "leave the expiry alone" value, so do not send this
   * call at all if that is what you meant.
   */
  expiryDate: string | null;
  /** Omit to keep the number already on file; most renewals reissue the same one. */
  licenseNumber?: string;
  issuingAuthority?: string;
  note?: string;
}

/**
 * Practising-licence renewal (Tier 2 item 40).
 *
 * Administrator roles only. The backend also refuses a practitioner
 * recording their own renewal even if they hold an admin role.
 */
@Injectable({ providedIn: 'root' })
export class CredentialingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/staff';

  recordRenewal(staffId: string, request: CredentialRenewalRequest): Observable<CredentialRenewal> {
    return this.http.post<CredentialRenewal>(
      `${this.baseUrl}/${staffId}/credentials/renew`,
      request,
    );
  }

  history(staffId: string): Observable<CredentialRenewal[]> {
    return this.http.get<CredentialRenewal[]>(`${this.baseUrl}/${staffId}/credentials/history`);
  }
}
