import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/*
 * Permission-matrix snapshots + audit — /permissions/matrix.
 * Class-level @PreAuthorize hasRole('SUPER_ADMIN') on every endpoint.
 * Global (no tenant scoping); bare DTOs/Lists, no wrapper.
 * Versions are per-environment and server-assigned.
 * POST /snapshots auto-writes a SNAPSHOT_PUBLISHED audit event — do not
 * double-log publishes from the client.
 * GET /audit is capped at the latest 50 rows server-side (no paging).
 */

export type MatrixEnvironment = 'CURRENT' | 'BASELINE' | 'STAGING' | 'PRODUCTION';
export const MATRIX_ENVIRONMENTS: MatrixEnvironment[] = [
  'CURRENT',
  'BASELINE',
  'STAGING',
  'PRODUCTION',
];

export type MatrixAuditAction = 'SNAPSHOT_PUBLISHED' | 'EXPORT_GENERATED' | 'COMPARISON_RUN';

export interface MatrixRow {
  domain: string;
  actions: string[];
  owners: string[];
}

export interface MatrixSnapshotRequest {
  sourceSnapshotId?: string;
  environment: MatrixEnvironment;
  label?: string;
  notes?: string;
  rows: MatrixRow[];
}

export interface MatrixSnapshot {
  id: string;
  sourceSnapshotId?: string;
  environment: MatrixEnvironment;
  version: number;
  label?: string;
  notes?: string;
  createdBy?: string;
  createdAt: string;
  rows: MatrixRow[];
}

export interface MatrixAuditEvent {
  id: string;
  action: MatrixAuditAction;
  leftEnvironment?: MatrixEnvironment;
  rightEnvironment?: MatrixEnvironment;
  snapshotId?: string;
  description?: string;
  initiatedBy?: string;
  createdAt: string;
  matrix: MatrixRow[];
  metadata?: string;
}

@Injectable({ providedIn: 'root' })
export class PermissionMatrixService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/permissions/matrix';

  createSnapshot(req: MatrixSnapshotRequest): Observable<MatrixSnapshot> {
    return this.http.post<MatrixSnapshot>(`${this.baseUrl}/snapshots`, req);
  }

  /** 404 when the environment has no snapshot yet. */
  latestSnapshot(environment: MatrixEnvironment): Observable<MatrixSnapshot> {
    const params = new HttpParams().set('environment', environment);
    return this.http.get<MatrixSnapshot>(`${this.baseUrl}/snapshots/latest`, { params });
  }

  listSnapshots(environment?: MatrixEnvironment): Observable<MatrixSnapshot[]> {
    let params = new HttpParams();
    if (environment) params = params.set('environment', environment);
    return this.http.get<MatrixSnapshot[]>(`${this.baseUrl}/snapshots`, { params });
  }

  getSnapshot(id: string): Observable<MatrixSnapshot> {
    return this.http.get<MatrixSnapshot>(`${this.baseUrl}/snapshots/${id}`);
  }

  listAudit(action?: MatrixAuditAction): Observable<MatrixAuditEvent[]> {
    let params = new HttpParams();
    if (action) params = params.set('action', action);
    return this.http.get<MatrixAuditEvent[]>(`${this.baseUrl}/audit`, { params });
  }
}
