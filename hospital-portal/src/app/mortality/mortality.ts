import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  DeathClosureSummary,
  DeathRecordResponse,
  MannerOfDeath,
  MaternalDeathTiming,
  MortalityRegister,
  MortalityService,
  PerinatalDeathType,
  PlaceOfDeath,
} from '../services/mortality.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

/**
 * The mortality register (Tier 2 item 29) — the first and only caller of every
 * `/mortality` endpoint.
 *
 * <p>Recording a death is irreversible and cascades: it closes admissions,
 * encounters, future appointments and recalls. So the form confirms first, and
 * the result reports exactly what was closed rather than doing it silently.
 */
@Component({
  selector: 'app-mortality',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe, PatientPickerComponent],
  templateUrl: './mortality.html',
  styleUrl: './mortality.scss',
})
export class MortalityComponent implements OnInit {
  private readonly mortality = inject(MortalityService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);
  private readonly translate = inject(TranslateService);

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

  /* ── Record modal ── */
  showRecordModal = signal(false);
  recording = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  form = this.emptyForm();

  /* ── Amend modal ── */
  showAmendModal = signal(false);
  amending = signal(false);
  amendForm = { amendmentReason: '', immediateCause: '', underlyingCause: '', notes: '' };

  ngOnInit(): void {
    const today = new Date();
    const start = new Date(today.getFullYear(), today.getMonth(), 1);
    this.from = this.toIsoDate(start);
    this.to = this.toIsoDate(today);
    this.loadRegister();
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
  }

  submitRecord(): void {
    if (!this.form.patientId || !this.form.diedAt || !this.form.immediateCause.trim()) return;
    // Irreversible and cascading — there is no un-death path, so ask first.
    if (!window.confirm(this.translate.instant('MORTALITY.RECORD_CONFIRM'))) return;

    this.recording.set(true);
    this.mortality
      .recordDeath({
        patientId: this.form.patientId,
        diedAt: this.form.diedAt,
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
        error: () => {
          this.toast.error(this.translate.instant('MORTALITY.RECORD_ERROR'));
          this.recording.set(false);
        },
      });
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
        error: () => {
          this.toast.error(this.translate.instant('MORTALITY.AMEND_ERROR'));
          this.amending.set(false);
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
      autopsyRequested: false,
      notes: '',
    };
  }
}
