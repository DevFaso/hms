import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Dhis2DataElementMapping,
  Dhis2DataElementMappingRequest,
  Dhis2ExportRun,
  Dhis2FacilityConfig,
  Dhis2FacilityConfigRequest,
  Dhis2Page,
  Dhis2TriggerRequest,
} from './dhis2.model';

/**
 * HTTP client for the DHIS2 ADX admin surface. All endpoints are gated
 * to ROLE_HOSPITAL_ADMIN / ROLE_SUPER_ADMIN on the backend; the UI
 * layer also hides the nav link from other roles.
 */
@Injectable({ providedIn: 'root' })
export class Dhis2Service {
  private readonly http = inject(HttpClient);
  private readonly base = '/admin/integrations/dhis2';
  private readonly exportsBase = '/admin/integrations/dhis2/exports';

  getFacilityConfig(hospitalId: string): Observable<Dhis2FacilityConfig> {
    const params = new HttpParams().set('hospitalId', hospitalId);
    return this.http.get<Dhis2FacilityConfig>(`${this.base}/facility`, { params });
  }

  upsertFacilityConfig(
    hospitalId: string,
    body: Dhis2FacilityConfigRequest,
  ): Observable<Dhis2FacilityConfig> {
    const params = new HttpParams().set('hospitalId', hospitalId);
    return this.http.put<Dhis2FacilityConfig>(`${this.base}/facility`, body, { params });
  }

  listMappings(
    hospitalId: string,
    datasetUid: string,
    page = 0,
    size = 20,
  ): Observable<Dhis2Page<Dhis2DataElementMapping>> {
    const params = new HttpParams()
      .set('hospitalId', hospitalId)
      .set('datasetUid', datasetUid)
      .set('page', page)
      .set('size', size);
    return this.http.get<Dhis2Page<Dhis2DataElementMapping>>(`${this.base}/mappings`, { params });
  }

  createMapping(
    hospitalId: string,
    body: Dhis2DataElementMappingRequest,
  ): Observable<Dhis2DataElementMapping> {
    const params = new HttpParams().set('hospitalId', hospitalId);
    return this.http.post<Dhis2DataElementMapping>(`${this.base}/mappings`, body, { params });
  }

  updateMapping(
    id: string,
    hospitalId: string,
    body: Dhis2DataElementMappingRequest,
  ): Observable<Dhis2DataElementMapping> {
    const params = new HttpParams().set('hospitalId', hospitalId);
    return this.http.put<Dhis2DataElementMapping>(`${this.base}/mappings/${id}`, body, {
      params,
    });
  }

  deleteMapping(id: string, hospitalId: string): Observable<void> {
    const params = new HttpParams().set('hospitalId', hospitalId);
    return this.http.delete<void>(`${this.base}/mappings/${id}`, { params });
  }

  triggerExport(body: Dhis2TriggerRequest): Observable<Dhis2ExportRun> {
    return this.http.post<Dhis2ExportRun>(`${this.exportsBase}/trigger`, body);
  }

  listRuns(hospitalId: string, page = 0, size = 20): Observable<Dhis2Page<Dhis2ExportRun>> {
    const params = new HttpParams()
      .set('hospitalId', hospitalId)
      .set('page', page)
      .set('size', size);
    return this.http.get<Dhis2Page<Dhis2ExportRun>>(`${this.exportsBase}/runs`, { params });
  }

  getRun(id: string): Observable<Dhis2ExportRun> {
    return this.http.get<Dhis2ExportRun>(`${this.exportsBase}/runs/${id}`);
  }
}
