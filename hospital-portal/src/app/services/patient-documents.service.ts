import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PatientDocumentResponse, PatientDocumentType } from './patient-portal.service';

/** Spring `Page<T>` as the staff routes return it (no ApiWrapper envelope). */
export interface PatientDocumentPage {
  content: PatientDocumentResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * Staff-side read of what a patient uploaded through the portal.
 *
 * Routes sit under /patients/{id}/documents so the auth interceptor adds the
 * bearer token and the active hospital (X-Hospital-Id); the backend refuses
 * without a hospital the patient is registered at. Bytes only ever stream
 * through the authenticated /download route as a blob — the response's
 * `fileUrl` is legacy and must not be used as a link.
 */
@Injectable({ providedIn: 'root' })
export class PatientDocumentsService {
  private readonly http = inject(HttpClient);

  list(
    patientId: string,
    documentType?: PatientDocumentType | null,
    page = 0,
    size = 50,
  ): Observable<PatientDocumentPage> {
    let params = new HttpParams().set('page', page).set('size', size).set('sort', 'createdAt,desc');
    if (documentType) {
      params = params.set('documentType', documentType);
    }
    return this.http.get<PatientDocumentPage>(`/patients/${patientId}/documents`, { params });
  }

  get(patientId: string, documentId: string): Observable<PatientDocumentResponse> {
    return this.http.get<PatientDocumentResponse>(`/patients/${patientId}/documents/${documentId}`);
  }

  downloadBlob(patientId: string, documentId: string): Observable<Blob> {
    return this.http.get(`/patients/${patientId}/documents/${documentId}/download`, {
      responseType: 'blob',
    });
  }
}
