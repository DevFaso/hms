import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  PortalOutcome,
  PortalOutcomeRequest,
  ProOutcomeType,
} from '../../services/patient-portal.service';

const OUTCOME_LABELS: Record<ProOutcomeType, string> = {
  PAIN_SCORE: 'Pain Score',
  MOOD: 'Mood',
  ENERGY_LEVEL: 'Energy Level',
  SLEEP_QUALITY: 'Sleep Quality',
  ANXIETY_LEVEL: 'Anxiety Level',
  FATIGUE: 'Fatigue',
  BREATHLESSNESS: 'Breathlessness',
  NAUSEA: 'Nausea',
  APPETITE: 'Appetite',
  GENERAL_WELLBEING: 'General Wellbeing',
};

@Component({
  selector: 'app-my-outcomes',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  templateUrl: './my-outcomes.html',
  styleUrl: './my-outcomes.scss',
})
export class MyOutcomesComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  loading = signal(true);
  outcomes = signal<PortalOutcome[]>([]);
  showForm = signal(false);
  submitting = signal(false);

  outcomeTypes: { value: ProOutcomeType; label: string }[] = (
    Object.keys(OUTCOME_LABELS) as ProOutcomeType[]
  ).map((k) => ({ value: k, label: OUTCOME_LABELS[k] }));

  form: PortalOutcomeRequest = this.blankForm();

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.portal.getMyOutcomes().subscribe({
      next: (data) => {
        this.outcomes.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  openForm(): void {
    this.form = this.blankForm();
    this.showForm.set(true);
  }

  cancelForm(): void {
    this.showForm.set(false);
  }

  submit(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.portal.reportMyOutcome(this.form).subscribe({
      next: (entry) => {
        this.outcomes.update((list) => [entry, ...list]);
        this.showForm.set(false);
        this.submitting.set(false);
      },
      error: () => this.submitting.set(false),
    });
  }

  scoreClass(score: number): string {
    if (score >= 7) return 'score-good';
    if (score >= 4) return 'score-mid';
    return 'score-poor';
  }

  private blankForm(): PortalOutcomeRequest {
    return {
      outcomeType: 'GENERAL_WELLBEING',
      score: 7,
      notes: '',
      reportDate: new Date().toISOString().slice(0, 10),
    };
  }
}
