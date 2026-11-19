import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The viewer's states (ImagingStudyViewerService.Status). */
export type ImagingViewerStatus =
  | 'AVAILABLE'
  | 'PROXY_DISABLED'
  | 'PACS_NOT_CONFIGURED'
  | 'NO_STUDY_LINKED'
  | 'STUDY_NOT_FOUND'
  | 'ACCESSION_AMBIGUOUS'
  | 'PACS_UNREACHABLE';

export interface ImagingStudyInstance {
  seriesInstanceUid: string;
  sopInstanceUid: string;
  seriesNumber?: number | null;
  instanceNumber?: number | null;
  numberOfFrames: number;
  modality?: string | null;
}

export interface ImagingStudyViewer {
  reportId: string;
  status: ImagingViewerStatus;
  linkedBy?: 'STUDY_UID' | 'ACCESSION' | null;
  instances: ImagingStudyInstance[];
  truncated: boolean;
}

/** A hospital's PACS settings as the admin reads them: never the password. */
export interface PacsSettings {
  hospitalId: string;
  enabled: boolean;
  dicomwebBaseUrl?: string | null;
  viewerUrlTemplate?: string | null;
  authUsername?: string | null;
  passwordSet: boolean;
  state: 'READY' | 'PROXY_DISABLED' | 'NOT_CONFIGURED';
  effectiveSource?: 'HOSPITAL' | 'PLATFORM' | null;
  platformProxyEnabled: boolean;
  platformFallbackConfigured: boolean;
  privateHostsAllowed: boolean;
}

export interface PacsSettingsUpdate {
  enabled: boolean;
  dicomwebBaseUrl?: string | null;
  viewerUrlTemplate?: string | null;
  authUsername?: string | null;
  /** Write-only: blank keeps the stored one. */
  authPassword?: string | null;
  clearPassword?: boolean;
}

export type PacsConnectionResult =
  | 'OK'
  | 'PROXY_DISABLED'
  | 'NOT_CONFIGURED'
  | 'UNREACHABLE'
  | 'UNAUTHORIZED'
  | 'REJECTED'
  | 'URL_REFUSED';

export interface PacsConnectionTest {
  result: PacsConnectionResult;
  source?: 'HOSPITAL' | 'PLATFORM' | null;
}

/**
 * The PACS path (V317): the in-app viewer, which streams rendered frames
 * through e-Keneya's own authenticated API, and the admin's PACS settings.
 */
@Injectable({ providedIn: 'root' })
export class ImagingPacsService {
  private readonly http = inject(HttpClient);

  /** QA D2467 (r64): is a PACS connected for this report's hospital? (No PACS is called.) */
  viewerReady(reportId: string): Observable<{ ready: boolean }> {
    return this.http.get<{ ready: boolean }>(
      `/imaging/dicom/reports/${encodeURIComponent(reportId)}/viewer-ready`,
    );
  }

  viewer(reportId: string): Observable<ImagingStudyViewer> {
    return this.http.get<ImagingStudyViewer>(
      `/imaging/dicom/reports/${encodeURIComponent(reportId)}/viewer`,
    );
  }

  /** One rendered frame as a Blob: fetched with the session, shown through an object URL. */
  frame(reportId: string, instance: ImagingStudyInstance, frame: number): Observable<Blob> {
    const path =
      `/imaging/dicom/reports/${encodeURIComponent(reportId)}` +
      `/series/${encodeURIComponent(instance.seriesInstanceUid)}` +
      `/instances/${encodeURIComponent(instance.sopInstanceUid)}` +
      `/frames/${frame}/rendered`;
    return this.http.get(path, { responseType: 'blob' });
  }

  settings(hospitalId: string): Observable<PacsSettings> {
    return this.http.get<PacsSettings>(
      `/hospitals/${encodeURIComponent(hospitalId)}/pacs-settings`,
    );
  }

  saveSettings(hospitalId: string, body: PacsSettingsUpdate): Observable<PacsSettings> {
    return this.http.put<PacsSettings>(
      `/hospitals/${encodeURIComponent(hospitalId)}/pacs-settings`,
      body,
    );
  }

  testConnection(hospitalId: string): Observable<PacsConnectionTest> {
    return this.http.post<PacsConnectionTest>(
      `/hospitals/${encodeURIComponent(hospitalId)}/pacs-settings/test`,
      {},
    );
  }
}
