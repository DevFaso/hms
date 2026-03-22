import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class DataExportService {
  private readonly http = inject(HttpClient);

  exportUsers(): void {
    this.http
      .get('/super-admin/export/users', { responseType: 'blob' })
      .subscribe((blob) => this.download(blob, 'users-export.csv'));
  }

  exportPatients(): void {
    this.http
      .get('/super-admin/export/patients', { responseType: 'blob' })
      .subscribe((blob) => this.download(blob, 'patients-export.csv'));
  }

  private download(blob: Blob, filename: string): void {
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    window.URL.revokeObjectURL(url);
  }
}
