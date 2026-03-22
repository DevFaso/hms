import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface SystemAlertDTO {
  id: string;
  alertType: string;
  severity: string;
  title: string;
  description: string;
  source: string;
  acknowledged: boolean;
  acknowledgedBy: string;
  acknowledgedAt: string;
  resolved: boolean;
  resolvedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface SystemAlertPage {
  content: SystemAlertDTO[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface AlertSummary {
  total: number;
  unacknowledged: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  last24h: number;
}

@Injectable({ providedIn: 'root' })
export class SystemMonitoringService {
  private readonly http = inject(HttpClient);

  listAlerts(page: number, size: number, severity?: string): Observable<SystemAlertPage> {
    const params: Record<string, string> = {
      page: String(page),
      size: String(size),
    };
    if (severity) {
      params['severity'] = severity;
    }
    return this.http.get<SystemAlertPage>('/super-admin/monitoring/alerts', { params });
  }

  getAlertSummary(): Observable<AlertSummary> {
    return this.http.get<AlertSummary>('/super-admin/monitoring/alerts/summary');
  }

  acknowledgeAlert(id: string): Observable<SystemAlertDTO> {
    return this.http.put<SystemAlertDTO>(`/super-admin/monitoring/alerts/${id}/acknowledge`, {});
  }

  resolveAlert(id: string): Observable<SystemAlertDTO> {
    return this.http.put<SystemAlertDTO>(`/super-admin/monitoring/alerts/${id}/resolve`, {});
  }
}
