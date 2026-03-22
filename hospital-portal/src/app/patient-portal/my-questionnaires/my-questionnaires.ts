import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, TitleCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  PortalQuestionnaire,
  PortalQuestionnaireResponse,
  QuestionItem,
  QuestionnaireSubmitRequest,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

type ActiveTab = 'pending' | 'submitted';

@Component({
  selector: 'app-my-questionnaires',
  standalone: true,
  imports: [CommonModule, FormsModule, DatePipe, TitleCasePipe],
  templateUrl: './my-questionnaires.html',
  styleUrl: './my-questionnaires.scss',
})
export class MyQuestionnairesComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  activeTab = signal<ActiveTab>('pending');
  loading = signal(true);
  submitting = signal(false);

  pending = signal<PortalQuestionnaire[]>([]);
  submitted = signal<PortalQuestionnaireResponse[]>([]);

  /** The questionnaire currently being filled out, or null if none open. */
  activeForm = signal<PortalQuestionnaire | null>(null);
  /** Tracks the patient's answers keyed by question id. */
  answers: Record<string, string> = {};

  ngOnInit(): void {
    this.loadPending();
    this.portal.getMySubmittedQuestionnaires().subscribe({
      next: (data) => this.submitted.set(data),
    });
  }

  private loadPending(): void {
    this.loading.set(true);
    this.portal.getMyPendingQuestionnaires().subscribe({
      next: (data) => {
        this.pending.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  switchTab(tab: ActiveTab): void {
    this.activeTab.set(tab);
    this.closeForm();
  }

  openForm(q: PortalQuestionnaire): void {
    this.answers = {};
    // Pre-fill YES_NO questions with 'no' to avoid empty required answers
    q.questions.forEach((question) => {
      if (question.type === 'YES_NO') {
        this.answers[question.id] = 'no';
      }
    });
    this.activeForm.set(q);
  }

  closeForm(): void {
    this.activeForm.set(null);
    this.answers = {};
  }

  isFormValid(): boolean {
    const q = this.activeForm();
    if (!q) return false;
    return q.questions
      .filter((item) => item.required)
      .every((item) => {
        const val = this.answers[item.id];
        return val !== undefined && val !== null && val.trim() !== '';
      });
  }

  submitForm(): void {
    const q = this.activeForm();
    if (!q || !this.isFormValid()) return;

    const dto: QuestionnaireSubmitRequest = {
      questionnaireId: q.id,
      answers: { ...this.answers },
    };

    this.submitting.set(true);
    this.portal.submitQuestionnaire(dto).subscribe({
      next: (response) => {
        this.submitted.update((list) => [response, ...list]);
        this.pending.update((list) => list.filter((item) => item.id !== q.id));
        this.closeForm();
        this.submitting.set(false);
        this.toast.success('Questionnaire submitted successfully');
      },
      error: () => {
        this.submitting.set(false);
        this.toast.error('Failed to submit questionnaire. Please try again.');
      },
    });
  }

  scaleRange(): number[] {
    return [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
  }

  questionTypeLabel(type: string): string {
    switch (type) {
      case 'YES_NO':
        return 'Yes / No';
      case 'SCALE':
        return 'Scale 1–10';
      case 'CHOICE':
        return 'Multiple choice';
      default:
        return 'Text';
    }
  }

  getAnswerDisplay(answers: Record<string, string>, questionId: string): string {
    return answers?.[questionId] ?? '—';
  }

  trackById(_: number, item: QuestionItem): string {
    return item.id;
  }
}
