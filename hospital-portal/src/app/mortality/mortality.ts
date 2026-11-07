import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  DeathClosureSummary,
  DeathDispute,
  DeathRecordRequest,
  DeathRecordResponse,
  MannerOfDeath,
  MaternalDeathTiming,
  MortalityRegister,
  MortalityService,
  PerinatalBirthStatus,
  PerinatalDeathType,
  PlaceOfDeath,
} from '../services/mortality.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { toServerDateTime } from '../shared/date-utils';
import { BackdropCloseDirective } from '../shared/a11y/backdrop-close.directive';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refusalReason } from '../shared/refusal-reason';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
import { isRecordedMale } from '../shared/patient-measures';
/**
 * The mortality register (Tier 2 item 29) — the first and only caller of every
 * `/mortality` endpoint.
 *
 * <p>Recording a death cascades: it closes admissions, encounters, future
 * appointments and recalls, and refuses new care. So the form confirms first, and
 * the result reports exactly what was closed rather than doing it silently. The one
 * way back (QA D1488) is an administrator's "entered in error", which leaves what
 * was closed closed.
 */
@Component({
  selector: 'app-mortality',
  standalone: true,
  imports: [
    DateTimePipe,
    SubmitGuardDirective,
    ModalDialogDirective,
    BackdropCloseDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
    PatientPickerComponent,
  ],
  templateUrl: './mortality.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './mortality.scss',
})
export class MortalityComponent implements OnInit {
  private readonly mortality = inject(MortalityService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  /** QA D1229: the in-app confirmation, never the browser's. */
  private readonly confirmService = inject(ConfirmService);

  loading = signal(false);
  register = signal<MortalityRegister | null>(null);
  selected = signal<DeathRecordResponse | null>(null);
  /** Shown after a death is recorded: what the cascade actually closed. */
  lastClosure = signal<DeathClosureSummary | null>(null);

  from = '';
  to = '';

  readonly canRecord = this.roleContext.hasAnyActiveRole([
    'ROLE_DOCTOR',
    'ROLE_SURGEON',
    'ROLE_MIDWIFE',
    'ROLE_SUPER_ADMIN',
  ]);

  /** QA D1488: who may mark a record entered in error; the server holds them to their own hospital. */
  readonly canCorrect = this.roleContext.hasAnyActiveRole([
    'ROLE_HOSPITAL_ADMIN',
    'ROLE_SUPER_ADMIN',
  ]);

  readonly placesOfDeath: PlaceOfDeath[] = ['FACILITY', 'HOME', 'IN_TRANSIT', 'OTHER', 'UNKNOWN'];
  readonly mannersOfDeath: MannerOfDeath[] = [
    'NATURAL',
    'ACCIDENT',
    'SUICIDE',
    'HOMICIDE',
    'UNDETERMINED',
    'PENDING_INVESTIGATION',
  ];
  readonly maternalTimings: MaternalDeathTiming[] = [
    'DURING_PREGNANCY',
    'DURING_LABOUR_OR_DELIVERY',
    'WITHIN_42_DAYS_POSTPARTUM',
    'LATE_MATERNAL',
  ];
  readonly perinatalTypes: PerinatalDeathType[] = ['STILLBIRTH', 'EARLY_NEONATAL', 'LATE_NEONATAL'];
  readonly birthStatuses: PerinatalBirthStatus[] = ['UNKNOWN', 'LIVE_BIRTH', 'STILLBIRTH'];

  /* ── Record modal ── */
  showRecordModal = signal(false);
  recording = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  form = this.emptyForm();

  /* ── Amend modal ── */
  showAmendModal = signal(false);
  amending = signal(false);
  amendForm = { amendmentReason: '', immediateCause: '', underlyingCause: '', notes: '' };

  /* ── Entered in error (QA D1488) ── */
  errorRecord = signal<DeathRecordResponse | null>(null);
  errorSubmitting = signal(false);
  errorReason = '';

  /* ── Disputes to review ── */
  disputes = signal<DeathDispute[]>([]);
  upholdTarget = signal<DeathDispute | null>(null);
  upholdReason = '';
  upholding = signal(false);

  ngOnInit(): void {
    const today = new Date();
    const start = new Date(today.getFullYear(), today.getMonth(), 1);
    this.from = this.toIsoDate(start);
    this.to = this.toIsoDate(today);
    this.loadRegister();
    this.loadDisputes();
  }

  loadDisputes(): void {
    if (!this.canCorrect) return;
    this.mortality.openDisputes().subscribe({
      next: (list) => this.disputes.set(list),
      error: () => this.toast.error(this.translate.instant('MORTALITY.LOAD_ERROR')),
    });
  }

  openUphold(dispute: DeathDispute): void {
    this.upholdReason = '';
    this.upholdTarget.set(dispute);
  }

  closeUphold(): void {
    this.upholdTarget.set(null);
  }

  async submitUphold(): Promise<void> {
    const dispute = this.upholdTarget();
    const reason = this.upholdReason.trim();
    if (!dispute || !reason) return;
    if (
      !(await this.confirmService.ask({
        titleKey: 'MORTALITY.UPHOLD_CONFIRM',
        confirmKey: 'MORTALITY.UPHOLD',
      }))
    )
      return;
    this.upholding.set(true);
    this.mortality.upholdDeath(dispute.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('MORTALITY.UPHOLD_DONE'));
        this.upholding.set(false);
        this.upholdTarget.set(null);
        this.loadDisputes();
        this.loadRegister();
      },
      error: (err: unknown) => {
        this.toast.error(refusalReason(err) ?? this.translate.instant('MORTALITY.UPHOLD_FAILED'));
        this.upholding.set(false);
      },
    });
  }

  /** Entered in error from a dispute: the row it needs is on the register, when this hospital recorded the death. */
  openEnteredInErrorFor(dispute: DeathDispute): void {
    const record = this.register()?.deaths.find((d) => d.id === dispute.deathRecordId);
    if (record) {
      this.openEnteredInError(record);
      return;
    }
    // A death outside the period shown: the same action, on its own record.
    this.mortality.getForPatient(dispute.patientId).subscribe({
      next: (found) => this.openEnteredInError(found),
      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('MORTALITY.ENTERED_IN_ERROR_FAILED'),
        ),
    });
  }

  loadRegister(): void {
    if (!this.from || !this.to) return;
    this.loading.set(true);
    this.mortality.getRegister(this.from, this.to).subscribe({
      next: (r) => {
        this.register.set(r);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('MORTALITY.LOAD_ERROR'));
        this.loading.set(false);
      },
    });
  }

  openRecord(): void {
    this.form = this.emptyForm();
    this.selectedPatient.set(null);
    this.lastClosure.set(null);
    this.showRecordModal.set(true);
  }

  closeRecord(): void {
    this.showRecordModal.set(false);
  }

  onPatientPicked(patient: PatientResponse | null): void {
    this.selectedPatient.set(patient);
    this.form.patientId = patient?.id ?? '';
    // QA D1490: the maternal fields are hidden for a patient recorded male, so nothing stays ticked behind them.
    if (this.maternalHidden()) {
      this.form.maternalDeath = false;
    }
  }

  /** QA D1490: a maternal death is not offered for a patient recorded male; unknown sex keeps it. */
  maternalHidden(): boolean {
    return isRecordedMale(this.selectedPatient()?.gender);
  }

  async submitRecord(): Promise<void> {
    if (!this.form.patientId || !this.form.diedAt || !this.form.immediateCause.trim()) return;
    // Cascading, and only an administrator can correct it, so ask first.
    if (
      !(await this.confirmService.ask({
        titleKey: 'MORTALITY.RECORD_CONFIRM',
        confirmKey: 'MORTALITY.RECORD_DEATH',
      }))
    )
      return;

    this.recording.set(true);
    this.mortality
      .recordDeath({
        patientId: this.form.patientId,
        // #948 review: wall clock in the picker, UTC on the server (which also
        // refuses a future death against its UTC clock).
        diedAt: toServerDateTime(this.form.diedAt),
        placeOfDeath: this.form.placeOfDeath,
        mannerOfDeath: this.form.mannerOfDeath,
        immediateCause: this.form.immediateCause.trim(),
        immediateCauseCode: this.form.immediateCauseCode.trim() || undefined,
        underlyingCause: this.form.underlyingCause.trim() || undefined,
        underlyingCauseCode: this.form.underlyingCauseCode.trim() || undefined,
        contributingCauses: this.form.contributingCauses.trim() || undefined,
        maternalDeath: this.form.maternalDeath,
        maternalDeathTiming: this.form.maternalDeath ? this.form.maternalDeathTiming : undefined,
        perinatalDeath: this.form.perinatalDeath,
        perinatalType: this.form.perinatalDeath ? this.form.perinatalType : undefined,
        // CR-08: optional facts, sent only with the classification they support.
        ...(this.form.perinatalDeath ? this.birthFacts() : {}),
        autopsyRequested: this.form.autopsyRequested,
        notes: this.form.notes.trim() || undefined,
      })
      .subscribe({
        next: (result) => {
          this.toast.success(this.translate.instant('MORTALITY.RECORDED'));
          this.recording.set(false);
          this.showRecordModal.set(false);
          // Surface the cascade rather than letting it happen unseen.
          this.lastClosure.set(result.closure);
          this.selected.set(result.deathRecord);
          this.loadRegister();
        },
        error: (err: unknown) => {
          this.toast.error(refusalReason(err) ?? this.translate.instant('MORTALITY.RECORD_ERROR'));
          this.recording.set(false);
        },
      });
  }

  /** Empty stays absent, which the server reads as unknown. */
  private birthFacts(): Pick<DeathRecordRequest, 'bornAt' | 'birthStatus' | 'gestationalAgeWeeks'> {
    return {
      bornAt: this.form.bornAt ? toServerDateTime(this.form.bornAt) : undefined,
      birthStatus: this.form.birthStatus,
      gestationalAgeWeeks: this.form.gestationalAgeWeeks ?? undefined,
    };
  }

  openDetail(record: DeathRecordResponse): void {
    this.selected.set(record);
  }

  closeDetail(): void {
    this.selected.set(null);
  }

  openAmend(record: DeathRecordResponse): void {
    this.amendForm = {
      amendmentReason: '',
      immediateCause: record.immediateCause ?? '',
      underlyingCause: record.underlyingCause ?? '',
      notes: record.notes ?? '',
    };
    this.selected.set(record);
    this.showAmendModal.set(true);
  }

  closeAmend(): void {
    this.showAmendModal.set(false);
  }

  submitAmend(): void {
    const record = this.selected();
    if (!record || !this.amendForm.amendmentReason.trim()) return;
    this.amending.set(true);
    this.mortality
      .amendDeathRecord(record.id, {
        amendmentReason: this.amendForm.amendmentReason.trim(),
        immediateCause: this.amendForm.immediateCause.trim() || undefined,
        underlyingCause: this.amendForm.underlyingCause.trim() || undefined,
        notes: this.amendForm.notes.trim() || undefined,
      })
      .subscribe({
        next: (updated) => {
          this.toast.success(this.translate.instant('MORTALITY.AMENDED'));
          this.amending.set(false);
          this.showAmendModal.set(false);
          this.selected.set(updated);
          this.loadRegister();
        },
        error: (err: unknown) => {
          this.toast.error(refusalReason(err) ?? this.translate.instant('MORTALITY.AMEND_ERROR'));
          this.amending.set(false);
        },
      });
  }

  openEnteredInError(record: DeathRecordResponse): void {
    this.errorReason = '';
    this.errorRecord.set(record);
  }

  closeEnteredInError(): void {
    this.errorRecord.set(null);
  }

  async submitEnteredInError(): Promise<void> {
    const record = this.errorRecord();
    const reason = this.errorReason.trim();
    if (!record || !reason) return;
    if (
      !(await this.confirmService.ask({
        titleKey: 'MORTALITY.ENTERED_IN_ERROR_CONFIRM',
        confirmKey: 'MORTALITY.MARK_ENTERED_IN_ERROR',
      }))
    )
      return;
    this.errorSubmitting.set(true);
    this.mortality.markEnteredInError(record.id, reason).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('MORTALITY.ENTERED_IN_ERROR_DONE'));
        this.errorSubmitting.set(false);
        this.errorRecord.set(null);
        this.selected.set(null);
        this.loadRegister();
        this.loadDisputes();
      },
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('MORTALITY.ENTERED_IN_ERROR_FAILED'),
        );
        this.errorSubmitting.set(false);
      },
    });
  }

  dismissClosure(): void {
    this.lastClosure.set(null);
  }

  /** True when the cascade actually closed something worth telling the operator about. */
  closureHasContent(c: DeathClosureSummary): boolean {
    return (
      c.admissionsClosed > 0 ||
      c.encountersClosed > 0 ||
      c.appointmentsCancelled > 0 ||
      c.recallsClosed > 0
    );
  }

  private toIsoDate(d: Date): string {
    // Local date, not toISOString(): that shifts to UTC and can report the
    // wrong day either side of midnight.
    const month = `${d.getMonth() + 1}`.padStart(2, '0');
    const day = `${d.getDate()}`.padStart(2, '0');
    return `${d.getFullYear()}-${month}-${day}`;
  }

  private emptyForm() {
    return {
      patientId: '',
      diedAt: '',
      placeOfDeath: 'FACILITY' as PlaceOfDeath,
      mannerOfDeath: 'NATURAL' as MannerOfDeath,
      immediateCause: '',
      immediateCauseCode: '',
      underlyingCause: '',
      underlyingCauseCode: '',
      contributingCauses: '',
      maternalDeath: false,
      maternalDeathTiming: 'WITHIN_42_DAYS_POSTPARTUM' as MaternalDeathTiming,
      perinatalDeath: false,
      perinatalType: 'STILLBIRTH' as PerinatalDeathType,
      birthStatus: 'UNKNOWN' as PerinatalBirthStatus,
      gestationalAgeWeeks: null as number | null,
      bornAt: '',
      autopsyRequested: false,
      notes: '',
    };
  }
}
