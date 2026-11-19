import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * One key image (or exported PDF) of an imaging study (V316). The bytes are
 * read from {@link ImagingImagesService.content}: an authenticated, audited
 * read, never a public URL.
 */
export interface ImagingStudyImage {
  id: string;
  imagingOrderId: string;
  /** The report it was attached to; null for an image on the study itself. */
  imagingReportId?: string | null;
  contentType: string;
  sizeBytes: number;
  caption?: string | null;
  uploadedAt?: string | null;
  uploadedByUserId?: string | null;
  /** Its report is signed (or the study has a signed report): it can no longer be removed. */
  locked: boolean;
  /** The caller may remove it now. */
  deletable: boolean;
  /** QA D2466: the caller may correct its caption (its uploader or a radiologist). */
  captionEditable?: boolean;
  /** QA D2464: the caller may withdraw it, with a reason, now that it is locked. */
  withdrawable?: boolean;
}

/** QA D2464 (r64): a withdrawn image as the record keeps it (never its bytes). */
export interface ImagingStudyImageWithdrawal {
  id: string;
  imagingOrderId: string;
  imagingReportId?: string | null;
  caption?: string | null;
  withdrawnAt?: string | null;
  withdrawnByUserId?: string | null;
  withdrawalReason?: string | null;
}

/** The types the server accepts, checked before the upload so the refusal is immediate. */
export const IMAGING_IMAGE_EXTENSIONS: readonly string[] = [
  '.jpg',
  '.jpeg',
  '.png',
  '.webp',
  '.pdf',
];
/** The server's limit (FileUploadService.MAX_UPLOAD_BYTES). */
export const IMAGING_IMAGE_MAX_BYTES = 10 * 1024 * 1024;

@Injectable({ providedIn: 'root' })
export class ImagingImagesService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/imaging';

  list(orderId: string): Observable<ImagingStudyImage[]> {
    return this.http.get<ImagingStudyImage[]>(`${this.baseUrl}/orders/${orderId}/images`);
  }

  /** The upload, with its progress events. */
  upload(
    orderId: string,
    file: File,
    reportId: string | null,
    caption: string | null,
  ): Observable<HttpEvent<ImagingStudyImage>> {
    const form = new FormData();
    form.append('file', file, file.name);
    let params = new HttpParams();
    if (reportId) params = params.set('reportId', reportId);
    if (caption?.trim()) params = params.set('caption', caption.trim());
    return this.http.post<ImagingStudyImage>(`${this.baseUrl}/orders/${orderId}/images`, form, {
      params,
      reportProgress: true,
      observe: 'events',
    });
  }

  content(imageId: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/images/${imageId}/content`, { responseType: 'blob' });
  }

  /** QA D2464 (r64): the study's withdrawn images, with when and why. */
  withdrawn(orderId: string): Observable<ImagingStudyImageWithdrawal[]> {
    return this.http.get<ImagingStudyImageWithdrawal[]>(
      `${this.baseUrl}/orders/${orderId}/images/withdrawn`,
    );
  }

  /** QA D2464 (r64): withdraw a locked image; the server keeps it for the record. */
  withdraw(imageId: string, reason: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/images/${imageId}/withdraw`, { reason });
  }

  /** QA D2466 (r64): correct an image's caption (audited). */
  updateCaption(imageId: string, caption: string): Observable<ImagingStudyImage> {
    return this.http.put<ImagingStudyImage>(`${this.baseUrl}/images/${imageId}/caption`, {
      caption,
    });
  }

  delete(imageId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/images/${imageId}`);
  }
}
