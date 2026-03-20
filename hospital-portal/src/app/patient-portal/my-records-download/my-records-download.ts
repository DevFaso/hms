import { Component, inject, signal } from '@angular/core';
import { PatientPortalService } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-records-download',
  standalone: true,
  imports: [],
  templateUrl: './my-records-download.html',
  styleUrl: './my-records-download.scss',
})
export class MyRecordsDownloadComponent {
  private readonly svc = inject(PatientPortalService);

  readonly downloading = signal<'pdf' | 'csv' | null>(null);
  readonly successMsg = signal<string | null>(null);
  readonly errorMsg = signal<string | null>(null);

  download(format: 'pdf' | 'csv'): void {
    this.downloading.set(format);
    this.successMsg.set(null);
    this.errorMsg.set(null);

    // downloadMyRecord uses HttpClient with blob responseType; subscribe completes after saving
    try {
      this.svc.downloadMyRecord(format);
      // Give the browser a moment then reset state
      setTimeout(() => {
        this.downloading.set(null);
        this.successMsg.set(`Your ${format.toUpperCase()} download has started.`);
        setTimeout(() => this.successMsg.set(null), 5000);
      }, 1500);
    } catch {
      this.downloading.set(null);
      this.errorMsg.set('Download failed. Please try again.');
    }
  }
}
