import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type PanelRole = 'PRIMARY_PROVIDER' | 'CHW';
export type PanelAssignmentStatus = 'ACTIVE' | 'ENDED';

export interface PanelAssignment {
  id: string;
  patientId: string;
  patientName?: string;
  providerStaffId: string;
  providerName?: string;
  panelRole: PanelRole;
  status: PanelAssignmentStatus;
  assignedOn: string;
  assignedByName?: string;
  endedOn?: string;
  endReason?: string;
}

export interface PanelPage {
  content: PanelAssignment[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface PanelOverviewRow {
  providerStaffId: string;
  providerName?: string;
  panelRole: PanelRole;
  activeCount: number;
}

export interface PanelAssignRequest {
  providerStaffId: string;
  panelRole: PanelRole;
  assignedOn?: string;
}

/** Panel management / empanelment (Tier 2 item 37). */
@Injectable({ providedIn: 'root' })
export class PanelService {
  private readonly http = inject(HttpClient);

  assign(patientId: string, req: PanelAssignRequest): Observable<PanelAssignment> {
    return this.http.post<PanelAssignment>(`/patients/${patientId}/panel`, req);
  }

  patientAssignments(patientId: string): Observable<PanelAssignment[]> {
    return this.http.get<PanelAssignment[]>(`/patients/${patientId}/panel`);
  }

  end(patientId: string, assignmentId: string, reason: string): Observable<PanelAssignment> {
    return this.http.put<PanelAssignment>(`/patients/${patientId}/panel/${assignmentId}/end`, {
      reason,
    });
  }

  myPanel(page = 0, size = 50): Observable<PanelPage> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<PanelPage>('/panels/my', { params });
  }

  providerPanel(staffId: string, page = 0, size = 50): Observable<PanelPage> {
    const params = new HttpParams().set('page', String(page)).set('size', String(size));
    return this.http.get<PanelPage>(`/panels/providers/${staffId}`, { params });
  }

  overview(): Observable<PanelOverviewRow[]> {
    return this.http.get<PanelOverviewRow[]>('/panels/overview');
  }
}
