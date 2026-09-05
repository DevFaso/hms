import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Input,
  OnChanges,
  OnInit,
  SimpleChanges,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, of, switchMap, tap } from 'rxjs';
import {
  PatientDocumentPage,
  PatientDocumentsService,
} from '../../services/patient-documents.service';
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

export const PAGE_SIZE = 25;

interface ListRequest {
  type: PatientDocumentType | null;
  page: number;
}

type ListOutcome = { ok: true; page: PatientDocumentPage } | { ok: false; message: string | null };

/**
 * Chart tab: what the patient uploaded through the portal (outside lab
 * reports, referral letters, insurance papers), readable by the care team.
 *
 * Read-only on purpose — uploads and deletions stay the patient's. Every
 * download is audited server-side; the tab only ever fetches bytes through
 * the authenticated route, never through the legacy `fileUrl`.
 *
 * Requests go through one switchMap pipeline so a slow earlier response can
 * never overwrite the filter or page the operator is now looking at.
 */
@Component({
  selector: 'app-documents-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './documents-tab.component.html',
  styleUrl: './documents-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DocumentsTabComponent implements OnInit, OnChanges {
  @Input({ required: true }) patientId = '';
  /**
   * The super-admin's cross-tenant scope, forwarded by the chart so a change
   * on the scope chip re-fetches under the new X-Hospital-Id. Non-super-admin
   * hosts leave it null; the interceptor sends their own hospital.
   */
  @Input() hospitalScope: string | null = null;

  private readonly documentsService = inject(PatientDocumentsService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly requests$ = new Subject<ListRequest>();

  readonly typeOptions = DOCUMENT_TYPE_OPTIONS;

  readonly documents = signal<PatientDocumentResponse[]>([]);
  readonly loading = signal(true);
  /** Server-provided message when the list cannot load (e.g. no active hospital). */
  readonly error = signal<string | null>(null);
  readonly typeFilter = signal<PatientDocumentType | ''>('');
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalElements = signal(0);
  readonly hasPrevious = computed(() => this.page() > 0);
  readonly hasNext = computed(() => this.page() + 1 < this.totalPages());
  readonly downloading = signal<string | null>(null);

  constructor() {
    this.requests$
      .pipe(
        tap(() => {
          this.loading.set(true);
          this.error.set(null);
        }),
        switchMap((req) =>
          this.documentsService.list(this.patientId, req.type, req.page, PAGE_SIZE).pipe(
            switchMap((page) => of<ListOutcome>({ ok: true, page })),
            catchError((err: unknown) =>
              of<ListOutcome>({ ok: false, message: this.serverMessage(err) }),
            ),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((outcome) => {
        if (outcome.ok) {
          this.documents.set(outcome.page.content ?? []);
          this.totalPages.set(outcome.page.totalPages ?? 0);
          this.totalElements.set(outcome.page.totalElements ?? 0);
          this.page.set(outcome.page.number ?? 0);
        } else {
          this.documents.set([]);
          this.totalPages.set(0);
          this.totalElements.set(0);
          this.error.set(outcome.message);
        }
        this.loading.set(false);
      });
  }

  ngOnInit(): void {
    this.load();
  }

  ngOnChanges(changes: SimpleChanges): void {
    // A scope change after init means a different X-Hospital-Id on the next
    // request; start over from the first page.
    if (changes['hospitalScope'] && !changes['hospitalScope'].firstChange) {
      this.page.set(0);
      this.load();
    }
  }

  load(): void {
    this.requests$.next({ type: this.typeFilter() || null, page: this.page() });
  }

  onFilterChange(value: PatientDocumentType | ''): void {
    this.typeFilter.set(value);
    this.page.set(0);
    this.load();
  }

  previousPage(): void {
    if (!this.hasPrevious()) return;
    this.page.update((p) => p - 1);
    this.load();
  }

  nextPage(): void {
    if (!this.hasNext()) return;
    this.page.update((p) => p + 1);
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
        // Defer revoke so Safari/WebKit has time to start reading the blob.
        setTimeout(() => URL.revokeObjectURL(url), 0);
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
