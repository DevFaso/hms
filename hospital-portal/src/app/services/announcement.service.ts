import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface AnnouncementResponse {
  id: string;
  text: string;
  date: string;
}

@Injectable({ providedIn: 'root' })
export class AnnouncementService {
  private readonly http = inject(HttpClient);

  list(limit = 50): Observable<AnnouncementResponse[]> {
    const params = new HttpParams().set('limit', String(limit));
    return this.http.get<AnnouncementResponse[]>('/announcements', { params });
  }

  getById(id: string): Observable<AnnouncementResponse> {
    return this.http.get<AnnouncementResponse>(`/announcements/${id}`);
  }

  create(text: string): Observable<AnnouncementResponse> {
    const params = new HttpParams().set('text', text);
    return this.http.post<AnnouncementResponse>('/announcements', null, { params });
  }

  update(id: string, text: string): Observable<AnnouncementResponse> {
    const params = new HttpParams().set('text', text);
    return this.http.put<AnnouncementResponse>(`/announcements/${id}`, null, { params });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/announcements/${id}`);
  }
}
