import {
  Component,
  EventEmitter,
  Input,
  Output,
  inject,
  signal,
  ChangeDetectionStrategy,
  DestroyRef,
  WritableSignal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  EncounterService,
  EncounterResponse,
  CheckOutRequest,
  AfterVisitSummary,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { serverLocalDate } from '../../shared/date-utils';
import { AuthService } from '../../auth/auth.service';
import { PatientService, PatientProblem } from '../../services/patient.service';
import { PrescriptionService, PrescriptionResponse } from '../../services/prescription.service';
import { ReferralService, ReferralResponse } from '../../services/referral.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

@Component({
  selector: 'app-checkout-dialog',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './checkout-dialog.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './checkout-dialog.component.scss',
})
export class CheckoutDialogComponent {
  /**
   * QA D960: the dialog opened blank, so the visit summary held only what the
   * doctor retyped. It now starts from the visit's own record for the doctor
   * to review: the diagnoses recorded during the visit (or the note's
   * assessment), its prescriptions and the referrals written that day.
   */
  @Input() set encounter(value: EncounterResponse | null) {
    const changed = value?.id !== this._encounter?.id;
    this._encounter = value;
    if (changed && value) this.prefill(value);
  }
  get encounter(): EncounterResponse | null {
    return this._encounter;
  }
  private _encounter: EncounterResponse | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() checkoutCompleted = new EventEmitter<AfterVisitSummary>();

  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly auth = inject(AuthService);
  private readonly patientService = inject(PatientService);
  private readonly prescriptionService = inject(PrescriptionService);
  private readonly referralService = inject(ReferralService);
  private readonly destroyRef = inject(DestroyRef);

  /** Roles all three reads admit (diagnoses, prescriptions, referrals by patient). */
  private static readonly PREFILL_ROLES = ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE'];
  private static readonly WITHDRAWN_RX = new Set(['CANCELLED', 'DISCONTINUED', 'PARTNER_REJECTED']);
  private static readonly CLOSED_REFERRAL = new Set(['CANCELLED', 'REJECTED']);

  /* ── Form fields ──────────────────────── */
  followUpInstructions = signal('');
  dischargeDiagnosesText = signal('');
  prescriptionSummary = signal('');
  referralSummary = signal('');
  patientEducationMaterials = signal('');
  followUpReason = signal('');
  followUpDate = signal('');

  /* ── UI state ─────────────────────────── */
  saving = signal(false);
  avs = signal<AfterVisitSummary | null>(null);

  get canSubmit(): boolean {
    return !!this.encounter && !this.saving();
  }

  submit(): void {
    if (!this.encounter || this.saving()) return;

    this.saving.set(true);

    const diagnoses = this.dischargeDiagnosesText()
      .split('\n')
      .map((d) => d.trim())
      .filter((d) => d.length > 0);

    const request: CheckOutRequest = {
      followUpInstructions: this.followUpInstructions() || undefined,
      dischargeDiagnoses: diagnoses.length ? diagnoses : undefined,
      prescriptionSummary: this.prescriptionSummary() || undefined,
      referralSummary: this.referralSummary() || undefined,
      patientEducationMaterials: this.patientEducationMaterials() || undefined,
    };

    // QA D426 (D321): a follow-up date entered without a reason was dropped.
    if (this.followUpReason().trim() || this.followUpDate()) {
      request.followUpAppointment = {
        reason: this.followUpReason().trim() || undefined,
        preferredDate: this.followUpDate() || undefined,
      };
    }

    this.encounterService.checkOut(this.encounter.id, request).subscribe({
      next: (summary) => {
        this.avs.set(summary);
        this.saving.set(false);
        this.toast.success(
          this.translate.instant('checkout.success') || 'Patient checked out successfully',
        );
        this.checkoutCompleted.emit(summary);
      },
      error: () => {
        this.saving.set(false);
        this.toast.error(
          this.translate.instant('checkout.error') || 'Check-out failed. Please try again.',
        );
      },
    });
  }

  close(): void {
    this.dismissed.emit();
  }

  private prefill(enc: EncounterResponse): void {
    const assessment = enc.note?.assessment?.trim() ?? '';
    if (!enc.patientId || !this.auth.hasAnyRole(CheckoutDialogComponent.PREFILL_ROLES)) {
      if (assessment) this.fillIfEmpty(this.dischargeDiagnosesText, assessment);
      return;
    }
    // The reader's calendar day of the visit (server times are UTC).
    const visitDay = serverLocalDate(enc.arrivalTimestamp || enc.encounterDate);
    forkJoin({
      problems: this.patientService
        .listDiagnoses(enc.patientId, { hospitalId: enc.hospitalId || undefined })
        .pipe(
          map((r) => r.items),
          catchError(() => of([] as PatientProblem[])),
        ),
      prescriptions: this.prescriptionService
        .list({ patientId: enc.patientId })
        .pipe(catchError(() => of([] as PrescriptionResponse[]))),
      referrals: this.referralService
        .getByPatient(enc.patientId)
        .pipe(catchError(() => of([] as ReferralResponse[]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(({ problems, prescriptions, referrals }) => {
        if (this._encounter?.id !== enc.id) return;
        const diagnoses = problems
          .filter((p) => p.status !== 'RESOLVED' && p.status !== 'INACTIVE')
          .filter((p) => !!visitDay && !!p.onsetDate && serverLocalDate(p.onsetDate) >= visitDay)
          .map((p) =>
            p.problemCode ? `${p.problemDisplay} (${p.problemCode})` : p.problemDisplay,
          );
        this.fillIfEmpty(
          this.dischargeDiagnosesText,
          diagnoses.length ? diagnoses.join('\n') : assessment,
        );
        this.fillIfEmpty(
          this.prescriptionSummary,
          prescriptions
            .filter((rx) => rx.encounterId === enc.id)
            .filter((rx) => !CheckoutDialogComponent.WITHDRAWN_RX.has(rx.status))
            .map((rx) =>
              [rx.medicationDisplayName || rx.medicationName, rx.dosage, rx.frequency, rx.duration]
                .filter((x) => !!x && String(x).trim())
                .join(' '),
            )
            .join('\n'),
        );
        this.fillIfEmpty(
          this.referralSummary,
          referrals
            .filter((r) => !CheckoutDialogComponent.CLOSED_REFERRAL.has(r.status))
            .filter((r) => !!visitDay && serverLocalDate(r.createdAt) >= visitDay)
            .map((r) =>
              [r.targetDepartmentName || r.targetSpecialty, r.referralReason]
                .filter((x) => !!x && String(x).trim())
                .join(' — '),
            )
            .join('\n'),
        );
      });
  }

  /** Never overwrite what the doctor already typed. */
  private fillIfEmpty(field: WritableSignal<string>, value: string): void {
    if (value && !field().trim()) field.set(value);
  }
}
