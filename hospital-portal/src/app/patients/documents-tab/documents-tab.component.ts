import { ChangeDetectionStrategy, Component, Input, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { PatientDocumentsService } from '../../services/patient-documents.service';
import {
  PatientDocumentResponse,
  PatientDocumentType,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

/** Same order as the patient's own upload form; labels reuse its keys. */
export const DOCUMENT_TYPE_OPTIONS: { value: PatientDocumentType; labelKey: string }[] = [
  { value: 'LAB_RESULT', labelKey: 'PORTAL.DOCUMENTS.TYPE_LAB_RESULT' },
  { value: 'IMAGING_REPORT', labelKey: 'PORTAL.DOCUMENTS.TYPE_IMAGING_REPORT' },
  { value: 'DISCHARGE_SUMMARY', labelKey: 'PORTAL.DOCUMENTS.TYPE_DISCHARGE_SUMMARY' },
  { value: 'REFERRAL_LETTER', labelKey: 'PORTAL.DOCUMENTS.TYPE_REFERRAL_LETTER' },
  { value: 'PRESCRIPTION', labelKey: 'PORTAL.DOCUMENTS.TYPE_PRESCRIPTION' },
  { value: 'INSURANCE_DOCUMENT', labelKey: 'PORTAL.DOCUMENTS.TYPE_INSURANCE_DOCUMENT' },
  { value: 'INVOICE', labelKey: 'PORTAL.DOCUMENTS.TYPE_INVOICE' },
  { value: 'IMMUNIZATION_RECORD', labelKey: 'PORTAL.DOCUMENTS.TYPE_IMMUNIZATION_RECORD' },
  { value: 'OTHER', labelKey: 'PORTAL.DOCUMENTS.TYPE_OTHER' },
];

/**
 * Chart tab: what the patient uploaded through the portal (outside lab
 * reports, referral letters, insurance papers), readable by the care team.
 *
 * Read-only on purpose — uploads and deletions stay the patient's. Every
 * download is audited server-side; the tab only ever fetches bytes through
 * the authenticated route, never through the legacy `fileUrl`.
 */
@Component({
  selector: 'app-documents-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './documents-tab.component.html',
  styleUrl: './documents-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentsTabComponent implements OnInit {
  @Input({ required: true }) patientId = '';

  private readonly documentsService = inject(PatientDocumentsService);
  private readonly toast = inject(ToastService);

  readonly typeOptions = DOCUMENT_TYPE_OPTIONS;

  readonly documents = signal<PatientDocumentResponse[]>([]);
  readonly loading = signal(true);
  /** Server-provided message when the list cannot load (e.g. no active hospital). */
  readonly error = signal<string | null>(null);
  readonly typeFilter = signal<PatientDocumentType | ''>('');
  readonly downloading = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.documentsService.list(this.patientId, this.typeFilter() || null).subscribe({
      next: (page) => {
        this.documents.set(page.content ?? []);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.documents.set([]);
        this.error.set(this.serverMessage(err));
        this.loading.set(false);
      },
    });
  }

  onFilterChange(value: PatientDocumentType | ''): void {
    this.typeFilter.set(value);
    this.load();
  }

  typeLabelKey(type: PatientDocumentType): string {
    return (
      this.typeOptions.find((o) => o.value === type)?.labelKey ?? 'PORTAL.DOCUMENTS.TYPE_OTHER'
    );
  }

  download(doc: PatientDocumentResponse): void {
    if (this.downloading()) return;
    this.downloading.set(doc.id);
    this.documentsService.downloadBlob(this.patientId, doc.id).subscribe({
      next: (blob) => {
        this.downloading.set(null);
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = doc.displayName || 'document';
        anchor.click();
        URL.revokeObjectURL(url);
      },
      error: () => {
        this.downloading.set(null);
        this.toast.error('PATIENT_DOCUMENTS.DOWNLOAD_FAILED');
      },
    });
  }

  /** Bytes → "12 KB" / "3.4 MB"; null when the server did not record a size. */
  formatSize(bytes: number | null | undefined): string | null {
    if (bytes === null || bytes === undefined) return null;
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  private serverMessage(err: unknown): string | null {
    if (err && typeof err === 'object' && 'error' in err) {
      const inner = (err as { error?: { message?: string } }).error;
      if (inner?.message) return inner.message;
    }
    return null;
  }
}
