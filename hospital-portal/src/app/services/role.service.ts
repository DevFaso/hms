import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface PermissionResponse {
  id: string;
  name: string;
  description: string;
}

export interface RoleResponse {
  id: string;
  name: string;
  authority: string;
  description: string;
  code: string;
  permissions: PermissionResponse[];
  createdAt: string;
  updatedAt: string;
}

export interface RoleCreateRequest {
  name: string;
  description?: string;
  code?: string;
}

@Injectable({ providedIn: 'root' })
export class RoleService {
  private readonly http = inject(HttpClient);

  list(): Observable<RoleResponse[]> {
    return this.http.get<RoleResponse[]>('/roles');
  }

  getById(id: string): Observable<RoleResponse> {
    return this.http.get<RoleResponse>(`/roles/${id}`);
  }

  create(req: RoleCreateRequest): Observable<RoleResponse> {
    return this.http.post<RoleResponse>('/roles', req);
  }

  update(id: string, req: RoleCreateRequest): Observable<RoleResponse> {
    return this.http.put<RoleResponse>(`/roles/${id}`, req);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/roles/${id}`);
  }

  assignPermissions(roleId: string, permissionIds: string[]): Observable<RoleResponse> {
    return this.http.put<RoleResponse>(`/roles/${roleId}/permissions`, permissionIds);
  }

  listPermissions(): Observable<PermissionResponse[]> {
    return this.http.get<PermissionResponse[]>('/permissions');
  }
}
