import { refusalReason } from '../../shared/refusal-reason';
import {
  Component,
  EventEmitter,
  Input,
  Output,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
  DestroyRef,
} from '@angular/core';
import { firstImplausibleVital } from '../../shared/vital-sign-limits';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  EncounterService,
  EncounterResponse,
  TriageSubmissionRequest,
  TriageSubmissionResponse,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';
import { RovingFocusDirective } from '../../shared/a11y/roving-focus.directive';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { PatientService } from '../../services/patient.service';
import { offersFundalHeight } from '../../shared/patient-measures';

import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
type ConsciousnessOption = 'ALERT' | 'NEW_CONFUSION' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';

@Component({
  selector: 'app-triage-form',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    CommonModule,
    FormsModule,
    TranslateModule,
    RovingFocusDirective,
    ModalDialogDirective,
  ],
  templateUrl: './triage-form.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './triage-form.component.scss',
})
export class TriageFormComponent {
  /**
   * QA D179: the chief complaint given at check-in is carried into the form,
   * so the nurse confirms or refines it instead of retyping it.
   */
  @Input() set encounter(value: EncounterResponse | null) {
    this._encounter = value;
    this.loadPatientSex(value);
    if (value?.chiefComplaint && !this.chiefComplaint()) {
      this.chiefComplaint.set(value.chiefComplaint);
    }
  }
  get encounter(): EncounterResponse | null {
    return this._encounter;
  }
  private _encounter: EncounterResponse | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() triageCompleted = new EventEmitter<TriageSubmissionResponse>();

  private readonly encounterService = inject(EncounterService);
  private readonly patientService = inject(PatientService);

  /**
   * QA D1077: the patient's recorded sex, read once per encounter. A fundal
   * height is not offered (nor sent) for a patient recorded as male.
   */
  readonly patientGender = signal<string | null>(null);
  readonly showFundalHeight = computed(() => offersFundalHeight(this.patientGender()));

  private loadPatientSex(value: EncounterResponse | null): void {
    this.patientGender.set(null);
    if (!value?.patientId) return;
    const asked = value.patientId;
    this.patientService
      .getById(asked, value.hospitalId || undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (p) => {
          if (this._encounter?.patientId === asked) this.patientGender.set(p?.gender ?? null);
        },
        // Unknown sex keeps the field: the server refuses it for a male patient.
        error: () => undefined,
      });
  }
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── Vital signs ────────────────────────── */
  temperatureCelsius = signal<number | null>(null);
  heartRateBpm = signal<number | null>(null);
  respiratoryRateBpm = signal<number | null>(null);
  systolicBpMmHg = signal<number | null>(null);
  diastolicBpMmHg = signal<number | null>(null);
  spo2Percent = signal<number | null>(null);
  weightKg = signal<number | null>(null);
  heightCm = signal<number | null>(null);
  painScale = signal<number | null>(null);
  /** QA D1031: the antenatal fundal height, in cm (optional). */
  fundalHeightCm = signal<number | null>(null);

  /* ── NEWS2 parameters (P3 #25b) ─────────── */
  onOxygen = signal(false);
  consciousnessLevel = signal<ConsciousnessOption | ''>('');
  readonly consciousnessOptions: ConsciousnessOption[] = [
    'ALERT',
    'NEW_CONFUSION',
    'VOICE',
    'PAIN',
    'UNRESPONSIVE',
  ];

  /* ── Clinical assessment ────────────────── */
  chiefComplaint = signal('');
  /** QA D1158: never preset (an untouched form reached the doctor as ESI 3); the nurse picks it. */
  esiScore = signal<number | null>(null);
  fallRisk = signal(false);
  fallRiskScore = signal<number | null>(null);

  /* ── Rooming ────────────────────────────── */
  roomAssignment = signal('');

  /* ── UI state ───────────────────────────── */
  saving = signal(false);

  /**
   * Localised once at construction. Kept as a stable array (not a getter) because
   * the template tracks each option by identity; the form is re-created per
   * encounter, so a language switch is picked up on the next open.
   */
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Built once, then rebuilt only when ngx-translate reports a language (or a
   * late-arriving translation file) — never inside change detection, so the
   * array keeps its identity for the template's tracking and a first paint
   * ahead of the language file does not freeze raw keys for the form's life.
   */
  private esiOptionsCache = this.buildEsiOptions();

  get esiOptions(): { value: number; label: string }[] {
    return this.esiOptionsCache;
  }

  private buildEsiOptions(): { value: number; label: string }[] {
    return [1, 2, 3, 4, 5].map((value) => ({
      value,
      label: this.translate.instant(`TRIAGE.ESI_OPTION_${value}`) as string,
    }));
  }

  constructor() {
    this.translate.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => (this.esiOptionsCache = this.buildEsiOptions()));
  }

  /**
   * Review of #971: Escape closes the dialog only while nothing has been
   * typed; once vitals are entered it would throw them away, so it is ignored
   * (the Close button still closes).
   */
  onEscape(): void {
    if (!this.hasEntries()) this.dismissed.emit();
  }

  hasEntries(): boolean {
    const vitals = [
      this.temperatureCelsius(),
      this.heartRateBpm(),
      this.respiratoryRateBpm(),
      this.systolicBpMmHg(),
      this.diastolicBpMmHg(),
      this.spo2Percent(),
      this.weightKg(),
      this.heightCm(),
      this.painScale(),
      this.fundalHeightCm(),
      this.fallRiskScore(),
    ];
    return (
      vitals.some((v) => v !== null) ||
      !!this.consciousnessLevel() ||
      this.onOxygen() ||
      this.fallRisk() ||
      !!this.roomAssignment().trim() ||
      this.chiefComplaint().trim() !== (this.encounter?.chiefComplaint ?? '').trim()
    );
  }

  /** QA D1158: a triage records at least one measured vital sign. */
  hasVitalSign(): boolean {
    return [
      this.temperatureCelsius(),
      this.heartRateBpm(),
      this.respiratoryRateBpm(),
      this.systolicBpMmHg(),
      this.diastolicBpMmHg(),
      this.spo2Percent(),
    ].some((v) => v !== null && v !== undefined && `${v}`.trim() !== '');
  }

  private esiChosen(): boolean {
    const esi = this.esiScore();
    return esi !== null && esi >= 1 && esi <= 5;
  }

  get canSubmit(): boolean {
    return !!this.encounter?.id && this.esiChosen() && this.hasVitalSign() && !this.saving();
  }

  /**
   * QA D1158: why "Soumettre le triage" refuses, for the submit guard: the
   * ESI (marked), else a translated "at least one vital sign" (the first vital marked).
   */
  submitBlockReason(): string | boolean {
    if (!this.encounter?.id || !this.esiChosen()) return true;
    if (!this.hasVitalSign()) return this.translate.instant('TRIAGE.VITAL_REQUIRED') as string;
    return false;
  }

  /** The fields the submit guard marks, matching {@link submitBlockReason}. */
  submitGuardFieldIds(): string[] {
    return this.esiChosen() ? ['temperature'] : ['esiScore'];
  }

  submit(): void {
    if (!this.encounter?.id) {
      this.toast.error(this.translate.instant('TRIAGE.NO_ENCOUNTER_SELECTED'));
      return;
    }
    const esiScore = this.esiScore();
    // QA D1158: the submit guard says why first; nothing empty is ever sent.
    if (esiScore === null || !this.hasVitalSign()) return;

    const implausible = firstImplausibleVital({
      temperatureCelsius: this.temperatureCelsius(),
      heartRateBpm: this.heartRateBpm(),
      respiratoryRateBpm: this.respiratoryRateBpm(),
      systolicBpMmHg: this.systolicBpMmHg(),
      diastolicBpMmHg: this.diastolicBpMmHg(),
      spo2Percent: this.spo2Percent(),
      weightKg: this.weightKg(),
      heightCm: this.heightCm(),
    });
    if (implausible) {
      this.toast.error(
        this.translate.instant(implausible.messageKey, {
          min: implausible.min,
          max: implausible.max,
        }),
      );
      return;
    }

    this.saving.set(true);

    const request: TriageSubmissionRequest = {
      esiScore,
      chiefComplaint: this.chiefComplaint() || undefined,
      temperatureCelsius: this.temperatureCelsius() ?? undefined,
      heartRateBpm: this.heartRateBpm() ?? undefined,
      respiratoryRateBpm: this.respiratoryRateBpm() ?? undefined,
      systolicBpMmHg: this.systolicBpMmHg() ?? undefined,
      diastolicBpMmHg: this.diastolicBpMmHg() ?? undefined,
      spo2Percent: this.spo2Percent() ?? undefined,
      weightKg: this.weightKg() ?? undefined,
      heightCm: this.heightCm() ?? undefined,
      onOxygen: this.onOxygen(),
      consciousnessLevel: this.consciousnessLevel() || undefined,
      painScale: this.painScale() ?? undefined,
      fundalHeightCm: this.showFundalHeight() ? (this.fundalHeightCm() ?? undefined) : undefined,
      fallRisk: this.fallRisk() || undefined,
      fallRiskScore: this.fallRiskScore() ?? undefined,
      roomAssignment: this.roomAssignment() || undefined,
    };

    this.encounterService.submitTriage(this.encounter.id, request).subscribe({
      next: (response) => {
        this.saving.set(false);
        this.toast.success(this.translate.instant('TRIAGE.COMPLETED'));
        this.triageCompleted.emit(response);
      },
      error: (err) => {
        this.saving.set(false);
        const msg = refusalReason(err) ?? this.translate.instant('TRIAGE.SUBMIT_FAILED');
        this.toast.error(msg);
      },
    });
  }
}
