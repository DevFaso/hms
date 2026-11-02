import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  PatientDocumentResponse,
  PatientDocumentType,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { currentLocale } from '../../shared/i18n/app-locale';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { blobRefusalReason } from '../../shared/blob-refusal';
import { refusalReason } from '../../shared/refusal-reason';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { refuseFields } from '../../shared/forms/invalid-fields';
/**
 * The largest file the upload endpoint accepts: spring.servlet.multipart
 * max-file-size is 10MB (the backend is being aligned on this one number).
 * Checked here so a patient is told before sending the bytes, not after a
 * rejected upload.
 */
export const MAX_PATIENT_DOCUMENT_BYTES = 10 * 1024 * 1024;

/** QA D1281: the upload form's required file input. */
const DOCUMENT_FILE_ID = 'portal-document-file';

/**
 * QA D1252: the label of EVERY document type the server can list. A Record
 * over the union, so a type added to PatientDocumentType without a label
 * does not compile; `scripts/lib/document-type-filter.test.mjs` checks the
 * union against the server's enum.
 */
const DOCUMENT_TYPE_LABELS: Readonly<Record<PatientDocumentType, string>> = {
  LAB_RESULT: 'PORTAL.DOCUMENTS.TYPE_LAB_RESULT',
  IMAGING_REPORT: 'PORTAL.DOCUMENTS.TYPE_IMAGING_REPORT',
  DISCHARGE_SUMMARY: 'PORTAL.DOCUMENTS.TYPE_DISCHARGE_SUMMARY',
  REFERRAL_LETTER: 'PORTAL.DOCUMENTS.TYPE_REFERRAL_LETTER',
  PRESCRIPTION: 'PORTAL.DOCUMENTS.TYPE_PRESCRIPTION',
  INSURANCE_DOCUMENT: 'PORTAL.DOCUMENTS.TYPE_INSURANCE_DOCUMENT',
  INVOICE: 'PORTAL.DOCUMENTS.TYPE_INVOICE',
  IMMUNIZATION_RECORD: 'PORTAL.DOCUMENTS.TYPE_IMMUNIZATION_RECORD',
  MEDICAL_CERTIFICATE: 'PORTAL.DOCUMENTS.TYPE_MEDICAL_CERTIFICATE',
  OTHER: 'PORTAL.DOCUMENTS.TYPE_OTHER',
};

/** Types a doctor issues and a patient never uploads (QA D961). */
const ISSUED_ONLY: ReadonlySet<PatientDocumentType> = new Set(['MEDICAL_CERTIFICATE']);

const ALL_TYPES = (Object.keys(DOCUMENT_TYPE_LABELS) as PatientDocumentType[]).map((value) => ({
  value,
  labelKey: DOCUMENT_TYPE_LABELS[value],
}));

/** What a patient may upload. */
const DOCUMENT_TYPES = ALL_TYPES.filter((t) => !ISSUED_ONLY.has(t.value));

@Component({
  selector: 'app-my-documents',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ServerTimePipe,
    CommonModule,
    DatePipe,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './my-documents.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./my-documents.component.scss', '../patient-portal-pages.scss'],
})
export class MyDocumentsComponent implements OnInit {
  private readonly portalService = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** QA D1229: the in-app confirmation, never the browser's. */
  private readonly confirmService = inject(ConfirmService);

  /** The upload form's choices. */
  readonly documentTypes = DOCUMENT_TYPES;
  /**
   * QA D1252: the "Filtrer par type" choices are every type the list can
   * hold; it reused the upload list, so a doctor's certificate vanished under
   * every filter.
   */
  readonly filterTypes = ALL_TYPES;

  /** The translation key of a document type; the raw enum is never shown (QA D344/D434). */
  typeLabelKey(type: PatientDocumentType): string {
    return DOCUMENT_TYPE_LABELS[type] ?? DOCUMENT_TYPE_LABELS.OTHER;
  }

  documents = signal<PatientDocumentResponse[]>([]);
  totalElements = signal(0);
  loading = signal(true);
  uploading = signal(false);
  showUploadForm = signal(false);
  deleting = signal<string | null>(null);

  filterType = signal<PatientDocumentType | ''>('');
  selectedFile = signal<File | null>(null);
  /** Set when the picked file is over MAX_PATIENT_DOCUMENT_BYTES; shown under the picker. */
  fileTooLarge = signal(false);
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
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL.DOCUMENTS.LOAD_FAILED'),
        );
        this.loading.set(false);
      },
    });
  }

  onFilterChange(): void {
    this.loadDocuments();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    if (file && file.size > MAX_PATIENT_DOCUMENT_BYTES) {
      // Refuse it here: clear the picker so the same file can be picked
      // again after it is shrunk, and keep the upload button disabled.
      this.fileTooLarge.set(true);
      this.selectedFile.set(null);
      input.value = '';
      return;
    }
    this.fileTooLarge.set(false);
    this.selectedFile.set(file);
  }

  uploadDocument(): void {
    const file = this.selectedFile();
    if (!file) {
      // QA D1281: the missing file is marked where it is chosen.
      const message = this.translate.instant('PORTAL.DOCUMENTS.FILE_REQUIRED');
      refuseFields(this.host.nativeElement, [DOCUMENT_FILE_ID], [DOCUMENT_FILE_ID], message);
      this.toast.error(message);
      return;
    }
    if (file.size > MAX_PATIENT_DOCUMENT_BYTES) {
      this.fileTooLarge.set(true);
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
          this.toast.success(this.translate.instant('PORTAL.DOCUMENTS.UPLOAD_SUCCESS'));
        },
        error: (err: unknown) => {
          this.uploading.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('PORTAL.DOCUMENTS.UPLOAD_FAILED'),
          );
        },
      });
  }

  downloading = signal<string | null>(null);

  /**
   * Blob download: document bytes stream only through the authenticated
   * endpoint (the public /uploads mapping is gone), and a bare href
   * carries no bearer token.
   */
  downloadDocument(doc: PatientDocumentResponse): void {
    if (this.downloading()) return;
    this.downloading.set(doc.id);
    this.portalService.downloadDocumentBlob(doc.id).subscribe({
      next: (blob) => {
        this.downloading.set(null);
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = doc.displayName || 'document';
        anchor.click();
        URL.revokeObjectURL(url);
      },
      error: (err: unknown) => {
        this.downloading.set(null);
        // QA D1184: a file the disk no longer holds says so, in the reader's language.
        void blobRefusalReason(err).then((reason) =>
          this.toast.error(reason ?? this.translate.instant('PORTAL.DOCUMENTS.DOWNLOAD_FAILED')),
        );
      },
    });
  }

  async deleteDocument(docId: string): Promise<void> {
    if (
      !(await this.confirmService.ask({
        titleKey: 'PORTAL.DOCUMENTS.DELETE_CONFIRM',
        confirmKey: 'COMMON.DELETE',
      }))
    )
      return;
    this.deleting.set(docId);
    this.portalService.deleteDocument(docId).subscribe({
      next: () => {
        this.documents.update((list) => list.filter((d) => d.id !== docId));
        this.totalElements.update((n) => Math.max(n - 1, 0));
        this.deleting.set(null);
        this.toast.success(this.translate.instant('PORTAL.DOCUMENTS.DELETE_SUCCESS'));
      },
      error: (err: unknown) => {
        this.deleting.set(null);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL.DOCUMENTS.DELETE_FAILED'),
        );
      },
    });
  }

  fileSizeKb(bytes: number): string {
    return new Intl.NumberFormat(currentLocale(), {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    }).format(bytes / 1024);
  }

  private resetForm(): void {
    this.selectedFile.set(null);
    this.fileTooLarge.set(false);
    this.selectedDocumentType.set('OTHER');
    this.collectionDate.set('');
    this.notes.set('');
    this.showUploadForm.set(false);
  }
}
