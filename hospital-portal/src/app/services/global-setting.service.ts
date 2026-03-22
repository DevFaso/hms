import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface GlobalSettingResponse {
  id: string;
  settingKey: string;
  settingValue: string;
  category: string;
  description: string;
  updatedBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface GlobalSettingRequest {
  settingKey: string;
  settingValue: string;
  category?: string;
  description?: string;
}

@Injectable({ providedIn: 'root' })
export class GlobalSettingService {
  private readonly http = inject(HttpClient);

  list(category?: string): Observable<GlobalSettingResponse[]> {
    if (category) {
      return this.http.get<GlobalSettingResponse[]>('/super-admin/settings', {
        params: { category },
      });
    }
    return this.http.get<GlobalSettingResponse[]>('/super-admin/settings');
  }

  getByKey(settingKey: string): Observable<GlobalSettingResponse> {
    return this.http.get<GlobalSettingResponse>(
      `/super-admin/settings/${encodeURIComponent(settingKey)}`,
    );
  }

  upsert(req: GlobalSettingRequest): Observable<GlobalSettingResponse> {
    return this.http.put<GlobalSettingResponse>('/super-admin/settings', req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/super-admin/settings/${id}`);
  }
}
