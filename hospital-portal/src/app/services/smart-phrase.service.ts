import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type SmartPhraseScope = 'GLOBAL' | 'HOSPITAL' | 'USER';

export interface SmartPhrase {
  id: string;
  trigger: string;
  title: string;
  expansion: string;
  scope: SmartPhraseScope;
  hospitalId?: string;
  ownerUserId?: string;
  specialty?: string;
  usageCount: number;
  lastUsedAt?: string;
}

export interface SmartPhraseRequest {
  trigger: string;
  title: string;
  expansion: string;
  scope: SmartPhraseScope;
  hospitalId?: string;
  ownerUserId?: string;
  specialty?: string;
}

/**
 * Client wrapper around the /smart-phrases REST endpoints. Powers the
 * dot-phrase autocomplete in the per-section EncounterNote form (item 5)
 * and the SmartPhrase library admin screen.
 */
@Injectable({ providedIn: 'root' })
export class SmartPhraseService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/smart-phrases';

  autocomplete(prefix: string, hospitalId?: string | null): Observable<SmartPhrase[]> {
    let params = new HttpParams().set('prefix', prefix);
    if (hospitalId) {
      params = params.set('hospitalId', hospitalId);
    }
    return this.http.get<SmartPhrase[]>(`${this.baseUrl}/autocomplete`, { params });
  }

  getById(id: string): Observable<SmartPhrase> {
    return this.http.get<SmartPhrase>(`${this.baseUrl}/${id}`);
  }

  create(req: SmartPhraseRequest): Observable<SmartPhrase> {
    return this.http.post<SmartPhrase>(this.baseUrl, req);
  }

  update(id: string, req: SmartPhraseRequest): Observable<SmartPhrase> {
    return this.http.put<SmartPhrase>(`${this.baseUrl}/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  recordUsage(id: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/usage`, {});
  }
}
