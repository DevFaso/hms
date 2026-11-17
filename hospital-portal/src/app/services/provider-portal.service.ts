import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  ProviderAuditPage,
  ProviderProfile,
  ProviderProfileUpdate,
  ProviderSettings,
  ProviderStaffMember,
} from './provider.model';

/**
 * A provider facility's own pages (provider plan US-2, §6.5): its profile,
 * its staff, its audit trail and the shell settings. The backend decides who
 * may call from live assignments at the facility; everyone else gets a 404
 * identical to an unmapped path, which the pages show as "not available".
 */
@Injectable({ providedIn: 'root' })
export class ProviderPortalService {
  private readonly http = inject(HttpClient);

  settings(): Observable<ProviderSettings> {
    return this.http.get<ProviderSettings>('/provider/settings');
  }

  profile(): Observable<ProviderProfile> {
    return this.http.get<ProviderProfile>('/provider/profile');
  }

  updateProfile(body: ProviderProfileUpdate): Observable<ProviderProfile> {
    return this.http.put<ProviderProfile>('/provider/profile', body);
  }

  staff(): Observable<ProviderStaffMember[]> {
    return this.http.get<ProviderStaffMember[]>('/provider/staff');
  }

  deactivate(userId: string): Observable<ProviderStaffMember> {
    return this.http.post<ProviderStaffMember>(
      `/provider/staff/${encodeURIComponent(userId)}/deactivate`,
      {},
    );
  }

  activate(userId: string): Observable<ProviderStaffMember> {
    return this.http.post<ProviderStaffMember>(
      `/provider/staff/${encodeURIComponent(userId)}/activate`,
      {},
    );
  }

  audit(page = 0, size = 20): Observable<ProviderAuditPage> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<ProviderAuditPage>('/provider/audit', { params });
  }
}
