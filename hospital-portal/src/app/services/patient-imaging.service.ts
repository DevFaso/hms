import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/**
 * One released report of a patient's imaging study, the patient's copy
 * (QA D2342): the server leaves out the status trail, the signature digest,
 * the critical-result escalation and the working measurements.
 */
export interface PatientImagingReport {
  id: string;
  reportStatus: string;
  reportVersion?: number | null;
  reportTitle?: string | null;
  bodyRegion?: string | null;
  technique?: string | null;
  findings?: string | null;
  impression?: string | null;
  recommendations?: string | null;
  comparisonStudies?: string | null;
  interpretingProviderName?: string | null;
  signedByName?: string | null;
  signedAt?: string | null;
  performedAt?: string | null;
}

/** One released key image of the study (#1223), read through the patient's own endpoint. */
export interface PatientImagingImage {
  id: string;
  imagingReportId?: string | null;
  contentType: string;
  sizeBytes: number;
  caption?: string | null;
  uploadedAt?: string | null;
}

/** A study with at least one released report: the original first, then its addenda. */
export interface PatientImagingStudy {
  imagingOrderId: string;
  modality?: string | null;
  studyType?: string | null;
  bodyRegion?: string | null;
  laterality?: string | null;
  orderedAt?: string | null;
  hospitalName?: string | null;
  reports: PatientImagingReport[];
  images: PatientImagingImage[];
}

interface Wrapped<T> {
  data?: T;
}

/**
 * QA D2342 (r63): the patient's released imaging reports and key images. With
 * a grantor's id the same reads go through the proxy endpoints, which check
 * the proxy grant (records permission) on the server.
 */
@Injectable({ providedIn: 'root' })
export class PatientImagingService {
  private readonly http = inject(HttpClient);
  private readonly base = '/me/patient';

  private root(grantorPatientId?: string | null): string {
    return grantorPatientId
      ? `${this.base}/proxy-access/${encodeURIComponent(grantorPatientId)}/imaging`
      : `${this.base}/imaging`;
  }

  studies(grantorPatientId?: string | null): Observable<PatientImagingStudy[]> {
    return this.http
      .get<Wrapped<PatientImagingStudy[]>>(this.root(grantorPatientId))
      .pipe(map((r) => r?.data ?? []));
  }

  image(imageId: string, grantorPatientId?: string | null): Observable<Blob> {
    return this.http.get(
      `${this.root(grantorPatientId)}/images/${encodeURIComponent(imageId)}/content`,
      { responseType: 'blob' },
    );
  }
}
