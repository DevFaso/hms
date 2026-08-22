import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Wristband + specimen label PDFs (P3 #23b). Fetched as authenticated
 * blobs (the billing getInvoicePdf pattern) and opened in a viewer tab
 * for printing — the wristband QR encodes the bare patient UUID, the
 * eMAR five-rights scan contract.
 */
@Injectable({ providedIn: 'root' })
export class PrintLabelService {
  private readonly http = inject(HttpClient);

  getWristbandPdf(patientId: string): Observable<Blob> {
    return this.http.get(`/patients/${patientId}/wristband.pdf`, { responseType: 'blob' });
  }

  getSpecimenLabelPdf(specimenId: string): Observable<Blob> {
    return this.http.get(`/lab-specimens/${specimenId}/label.pdf`, { responseType: 'blob' });
  }

  /** Open a PDF blob in a new tab for printing; revokes the URL after load. */
  openForPrint(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    const win = window.open(url, '_blank');
    // Give the viewer time to load before releasing the object URL; if the
    // popup was blocked there is nothing to revoke against, so fall back to
    // a timed release either way.
    if (win) {
      win.addEventListener('load', () => URL.revokeObjectURL(url));
    }
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }
}
