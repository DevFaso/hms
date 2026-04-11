import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  EncounterService,
  EncounterResponse,
  TriageSubmissionRequest,
  TriageSubmissionResponse,
} from '../../services/encounter.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-triage-form',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './triage-form.component.html',
  styleUrl: './triage-form.component.scss',
})
export class TriageFormComponent {
  @Input() encounter: EncounterResponse | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() triageCompleted = new EventEmitter<TriageSubmissionResponse>();

  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);

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

  /* ── Clinical assessment ────────────────── */
  chiefComplaint = signal('');
  esiScore = signal<number>(3);
  fallRisk = signal(false);
  fallRiskScore = signal<number | null>(null);

  /* ── Rooming ────────────────────────────── */
  roomAssignment = signal('');

  /* ── UI state ───────────────────────────── */
  saving = signal(false);

  readonly esiOptions = [
    { value: 1, label: 'ESI 1 – Resuscitation' },
    { value: 2, label: 'ESI 2 – Emergent' },
    { value: 3, label: 'ESI 3 – Urgent' },
    { value: 4, label: 'ESI 4 – Less Urgent' },
    { value: 5, label: 'ESI 5 – Non-Urgent' },
  ];

  get canSubmit(): boolean {
    return !!this.encounter?.id && this.esiScore() >= 1 && this.esiScore() <= 5 && !this.saving();
  }

  submit(): void {
    if (!this.encounter?.id) {
      this.toast.error('No encounter selected for triage');
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
      painScale: this.painScale() ?? undefined,
      fallRisk: this.fallRisk() || undefined,
      fallRiskScore: this.fallRiskScore() ?? undefined,
      roomAssignment: this.roomAssignment() || undefined,
    };

    this.encounterService.submitTriage(this.encounter.id, request).subscribe({
      next: (response) => {
        this.saving.set(false);
        this.toast.success('Triage completed successfully');
        this.triageCompleted.emit(response);
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err?.error?.message ?? 'Failed to submit triage. Please try again.';
        this.toast.error(msg);
      },
    });
  }
}
