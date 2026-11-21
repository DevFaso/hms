import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ImagingReadingContext, ImagingReportResponse } from './imaging.service';

/**
 * r63 (imaging completion): the radiologist's reading aids, kept beside
 * ImagingService: the « À signer » list (QA D2306) and the order's reading
 * context (age, sex, pregnancy, creatinine, prior studies).
 */
@Injectable({ providedIn: 'root' })
export class ImagingReadingService {
  private readonly http = inject(HttpClient);

  /** The unsigned reads waiting for the caller's signature, oldest first. */
  toSign(): Observable<ImagingReportResponse[]> {
    return this.http.get<ImagingReportResponse[]>('/imaging/results/to-sign');
  }

  readingContext(orderId: string): Observable<ImagingReadingContext> {
    return this.http.get<ImagingReadingContext>(
      `/imaging/orders/${encodeURIComponent(orderId)}/reading-context`,
    );
  }
}
