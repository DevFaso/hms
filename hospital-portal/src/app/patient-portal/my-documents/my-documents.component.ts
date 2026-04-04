import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  PatientDocumentResponse,
  PatientDocumentType,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

const DOCUMENT_TYPES: { value: PatientDocumentType; labelKey: string }[] = [
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

@Component({
  selector: 'app-my-documents',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule, TranslateModule],
  templateUrl: './my-documents.component.html',
  styleUrls: ['./my-documents.component.scss', '../patient-portal-pages.scss'],
})
export class MyDocumentsComponent implements OnInit {
  private readonly portalService = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly documentTypes = DOCUMENT_TYPES;

  documents = signal<PatientDocumentResponse[]>([]);
  totalElements = signal(0);
  loading = signal(true);
  uploading = signal(false);
  showUploadForm = signal(false);
  deleting = signal<string | null>(null);

  filterType = signal<PatientDocumentType | ''>('');
  selectedFile = signal<File | null>(null);
  selectedDocumentType = signal<PatientDocumentType>('OTHER');
  collectionDate = signal('');
  notes = signal('');

  ngOnInit(): void {
    this.loadDocuments();
  }

  loadDocuments(): void {
    this.loading.set(true);
    const filter = this.filterType() || undefined;
    this.portalService.listDocuments(filter as PatientDocumentType | undefined, 0, 50).subscribe({
      next: (result) => {
        this.documents.set(result.content);
        this.totalElements.set(result.totalElements);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('PORTAL.DOCUMENTS.LOAD_FAILED');
        this.loading.set(false);
      },
    });
  }

  onFilterChange(): void {
    this.loadDocuments();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files?.[0] ?? null);
  }

  uploadDocument(): void {
    const file = this.selectedFile();
    if (!file) {
      this.toast.error('PORTAL.DOCUMENTS.FILE_REQUIRED');
      return;
    }
    this.uploading.set(true);
    this.portalService
      .uploadDocument(
        file,
        this.selectedDocumentType(),
        this.collectionDate() || undefined,
        this.notes() || undefined,
      )
      .subscribe({
        next: (doc) => {
          this.documents.update((list) => [doc, ...list]);
          this.totalElements.update((n) => n + 1);
          this.resetForm();
          this.uploading.set(false);
          this.toast.success('PORTAL.DOCUMENTS.UPLOAD_SUCCESS');
        },
        error: () => {
          this.uploading.set(false);
          this.toast.error('PORTAL.DOCUMENTS.UPLOAD_FAILED');
        },
      });
  }

  deleteDocument(docId: string): void {
    if (!confirm(this.translate.instant('PORTAL.DOCUMENTS.DELETE_CONFIRM'))) return;
    this.deleting.set(docId);
    this.portalService.deleteDocument(docId).subscribe({
      next: () => {
        this.documents.update((list) => list.filter((d) => d.id !== docId));
        this.totalElements.update((n) => Math.max(n - 1, 0));
        this.deleting.set(null);
        this.toast.success('PORTAL.DOCUMENTS.DELETE_SUCCESS');
      },
      error: () => {
        this.deleting.set(null);
        this.toast.error('PORTAL.DOCUMENTS.DELETE_FAILED');
      },
    });
  }

  fileSizeKb(bytes: number): string {
    return (bytes / 1024).toFixed(1);
  }

  private resetForm(): void {
    this.selectedFile.set(null);
    this.selectedDocumentType.set('OTHER');
    this.collectionDate.set('');
    this.notes.set('');
    this.showUploadForm.set(false);
  }
}
