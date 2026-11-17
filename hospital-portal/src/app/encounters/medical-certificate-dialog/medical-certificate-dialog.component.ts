import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  MedicalCertificateService,
  MedicalCertificateType,
} from '../../services/medical-certificate.service';
import { PatientDocumentsService } from '../../services/patient-documents.service';
import { PatientDocumentResponse } from '../../services/patient-portal.service';
import { EncounterResponse } from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { serverLocalDate } from '../../shared/date-utils';
import { refusalReason } from '../../shared/refusal-reason';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { blobRefusalReason } from '../../shared/blob-refusal';
/**
 * QA D961: the doctor issues a dated, signed medical certificate or
 * sick-leave note from the visit, in French, English or Spanish. The PDF is
 * filed in the patient's documents and can be downloaded at once to print.
 */
@Component({
  selector: 'app-medical-certificate-dialog',
  standalone: true,
  imports: [SubmitGuardDirective, CommonModule, FormsModule, TranslateModule, ModalDialogDirective],
  templateUrl: './medical-certificate-dialog.component.html',
  styleUrl: './medical-certificate-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.Eager,
})
export class MedicalCertificateDialogComponent implements OnInit {
  @Input({ required: true }) encounter!: EncounterResponse;
  @Output() dismissed = new EventEmitter<void>();
  @Output() issued = new EventEmitter<PatientDocumentResponse>();

  private readonly service = inject(MedicalCertificateService);
  private readonly documents = inject(PatientDocumentsService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly types: MedicalCertificateType[] = ['SICK_LEAVE', 'ATTENDANCE', 'GENERAL'];
  readonly languages: ('fr' | 'en' | 'es')[] = ['fr', 'en', 'es'];

  /**
   * QA D1356: what the certificate attests starts EMPTY. It opened as a
   * one-day sick leave, so "Établir et signer" pressed on an untouched form
   * signed a certificate the doctor never wrote. The doctor chooses the type
   * and, for a sick leave, enters the days; only the first day is proposed
   * (the visit's day), shown once a sick leave is chosen.
   */
  type: MedicalCertificateType | '' = '';
  startDate = '';
  days: number | null = null;
  remarks = '';
  /** '' = the patient's language (the server resolves it); otherwise the doctor's pick. */
  language: '' | 'fr' | 'en' | 'es' = '';

  readonly saving = signal(false);
  readonly result = signal<PatientDocumentResponse | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.startDate = serverLocalDate(
      this.encounter?.arrivalTimestamp || this.encounter?.encounterDate,
    );
  }

  /** What the form still needs before it can be sent, as a message key, or null. */
  missing(): string | null {
    if (!this.type) return 'MEDICAL_CERTIFICATE.TYPE_REQUIRED';
    if (this.type === 'SICK_LEAVE' && (!this.days || this.days < 1 || this.days > 365)) {
      return 'MEDICAL_CERTIFICATE.DAYS_REQUIRED';
    }
    if (this.type === 'GENERAL' && !this.remarks.trim()) {
      return 'MEDICAL_CERTIFICATE.REMARKS_REQUIRED';
    }
    return null;
  }

  submit(): void {
    const type = this.type;
    if (this.saving() || this.missing() || !type) return;
    this.saving.set(true);
    this.error.set(null);
    this.service
      .issue(this.encounter.id, {
        type,
        startDate: this.type === 'SICK_LEAVE' && this.startDate ? this.startDate : undefined,
        days: this.type === 'SICK_LEAVE' ? (this.days ?? undefined) : undefined,
        remarks: this.remarks.trim() || undefined,
        language: this.language || undefined,
      })
      .subscribe({
        next: (doc) => {
          this.saving.set(false);
          this.result.set(doc);
          this.toast.success(this.translate.instant('MEDICAL_CERTIFICATE.ISSUED'));
          this.issued.emit(doc);
        },
        error: (err) => {
          this.saving.set(false);
          this.error.set(
            refusalReason(err) ?? this.translate.instant('MEDICAL_CERTIFICATE.ISSUE_FAILED'),
          );
        },
      });
  }

  download(): void {
    const doc = this.result();
    if (!doc) return;
    this.documents.downloadBlob(doc.patientId, doc.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = doc.displayName || 'certificat.pdf';
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 0);
      },
      // QA D1184: a file the disk no longer holds says so, in the reader's language.
      error: (err: unknown) =>
        void blobRefusalReason(err).then((reason) =>
          this.toast.error(reason ?? this.translate.instant('MEDICAL_CERTIFICATE.DOWNLOAD_FAILED')),
        ),
    });
  }
}
