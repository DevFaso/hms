import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface AuditEventLog {
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

export interface AuditEventTypeStatus {
  eventType: string;
  count: number;
}

@Injectable({ providedIn: 'root' })
export class AuditLogService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/audit-logs';

  list(params?: {
    page?: number;
    size?: number;
    eventType?: string;
    fromDate?: string;
    toDate?: string;
  }): Observable<{ content: AuditEventLog[] }> {
    let httpParams = new HttpParams();
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size) httpParams = httpParams.set('size', params.size);
    if (params?.eventType) httpParams = httpParams.set('eventType', params.eventType);
    if (params?.fromDate) httpParams = httpParams.set('fromDate', params.fromDate);
    if (params?.toDate) httpParams = httpParams.set('toDate', params.toDate);
    return this.http.get<{ content: AuditEventLog[] }>(this.baseUrl, { params: httpParams });
  }

  getByUser(
    userId: string,
    params?: { page?: number; size?: number },
  ): Observable<{ content: AuditEventLog[] }> {
    let httpParams = new HttpParams();
    if (params?.page != null) httpParams = httpParams.set('page', params.page);
    if (params?.size) httpParams = httpParams.set('size', params.size);
    return this.http.get<{ content: AuditEventLog[] }>(`${this.baseUrl}/user/${userId}`, {
      params: httpParams,
    });
  }

  getEventTypeStatus(): Observable<AuditEventTypeStatus[]> {
    return this.http.get<AuditEventTypeStatus[]>(`${this.baseUrl}/event-types-summary`);
  }

  create(event: {
    eventType: string;
    eventDescription: string;
    details?: string;
  }): Observable<AuditEventLog> {
    return this.http.post<AuditEventLog>(this.baseUrl, event);
  }
}
