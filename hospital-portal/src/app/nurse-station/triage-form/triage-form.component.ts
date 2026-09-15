import {
  Component,
  EventEmitter,
  Input,
  Output,
  inject,
  signal,
  ChangeDetectionStrategy,
  DestroyRef,
} from '@angular/core';
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

type ConsciousnessOption = 'ALERT' | 'NEW_CONFUSION' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';

@Component({
  selector: 'app-triage-form',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, RovingFocusDirective],
  templateUrl: './triage-form.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './triage-form.component.scss',
})
export class TriageFormComponent {
  @Input() encounter: EncounterResponse | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() triageCompleted = new EventEmitter<TriageSubmissionResponse>();

  private readonly encounterService = inject(EncounterService);
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
  esiScore = signal<number>(3);
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

  get canSubmit(): boolean {
    return !!this.encounter?.id && this.esiScore() >= 1 && this.esiScore() <= 5 && !this.saving();
  }

  submit(): void {
    if (!this.encounter?.id) {
      this.toast.error(this.translate.instant('TRIAGE.NO_ENCOUNTER_SELECTED'));
      return;
    }

    this.saving.set(true);

    const request: TriageSubmissionRequest = {
      esiScore: this.esiScore(),
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
        const msg = err?.error?.message ?? this.translate.instant('TRIAGE.SUBMIT_FAILED');
        this.toast.error(msg);
      },
    });
  }
}
