import {
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  PortalAppointment,
  QuestionnaireDTO,
  QuestionnaireQuestion,
  PreCheckInRequest,
  QuestionnaireSubmission,
} from '../../../services/patient-portal.service';

@Component({
  selector: 'app-pre-checkin-form',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './pre-checkin-form.component.html',
  styleUrl: './pre-checkin-form.component.scss',
})
export class PreCheckinFormComponent implements OnInit, OnChanges {
  @Input() appointment: PortalAppointment | null = null;
  @Output() dismissed = new EventEmitter<void>();
  @Output() preCheckinCompleted = new EventEmitter<void>();

  private readonly portal = inject(PatientPortalService);

  // ── State ──────────────────────────────────────────────────────────
  step = signal<'demographics' | 'questionnaires' | 'review'>('demographics');
  saving = signal(false);
  errorMessage = signal<string | null>(null);
  successMessage = signal<string | null>(null);

  // ── Questionnaires ─────────────────────────────────────────────────
  questionnaires = signal<QuestionnaireDTO[]>([]);
  parsedQuestions = signal<Map<string, QuestionnaireQuestion[]>>(new Map());
  answers = signal<Map<string, Record<string, unknown>>>(new Map());

  // ── Demographics form ──────────────────────────────────────────────
  phoneNumber = '';
  email = '';
  addressLine1 = '';
  city = '';
  state = '';
  zipCode = '';
  emergencyContactName = '';
  emergencyContactPhone = '';
  emergencyContactRelationship = '';
  insuranceProvider = '';
  insuranceMemberId = '';
  insurancePlan = '';

  // ── Consent ────────────────────────────────────────────────────────
  consentAcknowledged = false;

  ngOnInit(): void {
    this.loadQuestionnaires();
  }

  ngOnChanges(): void {
    this.loadQuestionnaires();
  }

  private loadQuestionnaires(): void {
    if (!this.appointment) return;
    this.portal.getQuestionnairesForAppointment(this.appointment.id).subscribe({
      next: (qs) => {
        this.questionnaires.set(qs);
        const parsed = new Map<string, QuestionnaireQuestion[]>();
        const answersMap = new Map<string, Record<string, unknown>>();
        for (const q of qs) {
          try {
            const questions = JSON.parse(q.questions) as QuestionnaireQuestion[];
            parsed.set(q.id, questions);
            const defaultAnswers: Record<string, unknown> = {};
            for (const question of questions) {
              defaultAnswers[question.id] = question.type === 'YES_NO' ? false : '';
            }
            answersMap.set(q.id, defaultAnswers);
          } catch {
            parsed.set(q.id, []);
            answersMap.set(q.id, {});
          }
        }
        this.parsedQuestions.set(parsed);
        this.answers.set(answersMap);
      },
    });
  }

  goToStep(s: 'demographics' | 'questionnaires' | 'review'): void {
    this.step.set(s);
    this.errorMessage.set(null);
  }

  get canSubmit(): boolean {
    return this.consentAcknowledged && !this.saving();
  }

  onAnswerChange(questionnaireId: string, questionId: string, value: unknown): void {
    const current = this.answers();
    const existing = current.get(questionnaireId);
    const qAnswers = existing ? { ...existing } : ({} as Record<string, unknown>);
    qAnswers[questionId] = value;
    const updated = new Map(current);
    updated.set(questionnaireId, qAnswers);
    this.answers.set(updated);
  }

  submit(): void {
    if (!this.appointment || this.saving()) return;
    this.saving.set(true);
    this.errorMessage.set(null);

    const questionnaireResponses: QuestionnaireSubmission[] = [];
    for (const q of this.questionnaires()) {
      const qAnswers = this.answers().get(q.id);
      if (qAnswers && Object.keys(qAnswers).length > 0) {
        questionnaireResponses.push({
          questionnaireId: q.id,
          responses: JSON.stringify(qAnswers),
        });
      }
    }

    const dto: PreCheckInRequest = {
      appointmentId: this.appointment.id,
      phoneNumber: this.phoneNumber || undefined,
      email: this.email || undefined,
      addressLine1: this.addressLine1 || undefined,
      city: this.city || undefined,
      state: this.state || undefined,
      zipCode: this.zipCode || undefined,
      emergencyContactName: this.emergencyContactName || undefined,
      emergencyContactPhone: this.emergencyContactPhone || undefined,
      emergencyContactRelationship: this.emergencyContactRelationship || undefined,
      insuranceProvider: this.insuranceProvider || undefined,
      insuranceMemberId: this.insuranceMemberId || undefined,
      insurancePlan: this.insurancePlan || undefined,
      questionnaireResponses,
      consentAcknowledged: this.consentAcknowledged,
    };

    this.portal.submitPreCheckIn(this.appointment.id, dto).subscribe({
      next: () => {
        this.saving.set(false);
        this.successMessage.set('Pre-check-in completed successfully!');
        setTimeout(() => this.preCheckinCompleted.emit(), 1500);
      },
      error: (err) => {
        this.saving.set(false);
        this.errorMessage.set(
          err?.error?.message || 'Failed to submit pre-check-in. Please try again.',
        );
      },
    });
  }

  close(): void {
    this.dismissed.emit();
  }

  trackByIndex(index: number): number {
    return index;
  }
}
