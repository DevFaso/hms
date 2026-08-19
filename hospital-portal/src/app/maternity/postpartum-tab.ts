import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PostpartumService,
  NewbornAssessmentRequest,
  NewbornAssessmentResponse,
  NewbornFollowUpAction,
  PostpartumBladderStatus,
  PostpartumFundusTone,
  PostpartumLochiaAmount,
  PostpartumLochiaCharacter,
  PostpartumMoodStatus,
  PostpartumObservationRequest,
  PostpartumObservationResponse,
  PostpartumSchedule,
  PostpartumSleepQuality,
  PostpartumSupportStatus,
} from '../services/postpartum.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

@Component({
  selector: 'app-postpartum-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './postpartum-tab.html',
  styleUrl: './maternity.scss',
})
export class PostpartumTabComponent {
  private readonly postpartumService = inject(PostpartumService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  patient = signal<PatientResponse | null>(null);
  section = signal<'mother' | 'newborn'>('mother');

  schedule = signal<PostpartumSchedule | null>(null);
  observations = signal<PostpartumObservationResponse[]>([]);
  assessments = signal<NewbornAssessmentResponse[]>([]);
  loading = signal(false);

  readonly fundusTones: PostpartumFundusTone[] = [
    'FIRM',
    'SLIGHTLY_BOGGY',
    'BOGGY',
    'DEVIATED',
    'NOT_PALPABLE',
    'UNKNOWN',
  ];
  readonly bladderStatuses: PostpartumBladderStatus[] = [
    'VOIDED_SPONTANEOUSLY',
    'VOIDED_WITH_ASSISTANCE',
    'DISTENDED',
    'CATHETER_IN_PLACE',
    'NEEDS_STRAIGHT_CATHETERIZATION',
    'UNABLE_TO_VOID',
    'UNKNOWN',
  ];
  readonly lochiaAmounts: PostpartumLochiaAmount[] = [
    'NONE',
    'SCANT',
    'LIGHT',
    'MODERATE',
    'HEAVY',
    'EXCESSIVE',
  ];
  readonly lochiaCharacters: PostpartumLochiaCharacter[] = [
    'RUBRA',
    'SEROSA',
    'ALBA',
    'BROWN_TINGED',
    'FRESH_RED',
    'FOUL_ODOR',
    'WITH_CLOTS',
    'OTHER',
  ];
  readonly moodStatuses: PostpartumMoodStatus[] = [
    'CALM',
    'CONTENT',
    'ANXIOUS',
    'DEPRESSED',
    'TEARFUL',
    'IRRITABLE',
    'WITHDRAWN',
    'EUPHORIC',
    'OTHER',
  ];
  readonly supportStatuses: PostpartumSupportStatus[] = [
    'ROBUST',
    'ADEQUATE',
    'LIMITED',
    'NONE',
    'UNKNOWN',
  ];
  readonly sleepQualities: PostpartumSleepQuality[] = [
    'RESTED',
    'ADEQUATE',
    'INTERRUPTED',
    'EXHAUSTED',
    'UNKNOWN',
  ];
  readonly followUpActions: NewbornFollowUpAction[] = [
    'NICU_CONSULT',
    'PEDIATRICIAN_NOTIFICATION',
    'RESPIRATORY_SUPPORT',
    'GLUCOSE_MONITORING',
    'THERMAL_SUPPORT',
    'SEPSIS_EVALUATION',
    'OXYGEN_THERAPY',
    'FEEDING_SUPPORT',
    'MONITORING_RECHECK',
    'PARENT_EDUCATION_REINFORCEMENT',
  ];

  /* ── Observation modal ── */
  showObservationModal = signal(false);
  observationSaving = signal(false);
  observationForm: PostpartumObservationRequest = {};

  /* ── Newborn assessment modal ── */
  showAssessmentModal = signal(false);
  assessmentSaving = signal(false);
  assessmentForm: NewbornAssessmentRequest = {};
  selectedFollowUps = new Set<NewbornFollowUpAction>();

  onPatientPicked(p: PatientResponse | null): void {
    this.patient.set(p);
    this.schedule.set(null);
    this.observations.set([]);
    this.assessments.set([]);
    if (p) this.load();
  }

  setSection(section: 'mother' | 'newborn'): void {
    this.section.set(section);
  }

  load(): void {
    const patient = this.patient();
    if (!patient) return;
    this.loading.set(true);
    this.postpartumService.schedule(patient.id).subscribe({
      next: (schedule) => this.schedule.set(schedule),
      error: () => this.schedule.set(null),
    });
    this.postpartumService.recentObservations(patient.id, 20).subscribe({
      next: (list) => {
        this.observations.set(list ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.observations.set([]);
        this.loading.set(false);
      },
    });
    this.postpartumService.recentNewbornAssessments(patient.id, 20).subscribe({
      next: (list) => this.assessments.set(list ?? []),
      error: () => this.assessments.set([]),
    });
  }

  phaseLabelKey(schedule: PostpartumSchedule): string {
    return `POSTPARTUM.PHASE_${schedule.phase}`;
  }

  alertClass(severity: string): string {
    switch (severity) {
      case 'URGENT':
        return 'risk-badge risk-high';
      case 'CAUTION':
        return 'risk-badge risk-moderate';
      default:
        return 'risk-badge risk-low';
    }
  }

  /** Local-timezone yyyy-MM-ddTHH:mm for datetime-local inputs. */
  private nowLocalDatetime(): string {
    const d = new Date();
    const pad = (n: number): string => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  /* ── Observation ── */

  openObservation(): void {
    this.observationForm = { observationTime: this.nowLocalDatetime() };
    this.showObservationModal.set(true);
  }

  closeObservation(): void {
    this.showObservationModal.set(false);
  }

  submitObservation(): void {
    const patient = this.patient();
    if (!patient) return;
    this.observationForm.recordedByStaffId = this.auth.getUserProfile()?.staffId ?? undefined;
    this.observationSaving.set(true);
    this.postpartumService.createObservation(patient.id, this.observationForm).subscribe({
      next: (created) => {
        this.toast.success(this.translate.instant('POSTPARTUM.OBSERVATION_SAVED'));
        this.observationSaving.set(false);
        this.closeObservation();
        if (created.schedule) this.schedule.set(created.schedule);
        this.observations.update((list) => [created, ...list]);
        for (const alert of created.alerts ?? []) {
          if (alert.severity === 'URGENT') this.toast.error(alert.message);
          else this.toast.info(alert.message);
        }
      },
      error: () => {
        this.toast.error(this.translate.instant('POSTPARTUM.OBSERVATION_SAVE_ERROR'));
        this.observationSaving.set(false);
      },
    });
  }

  /* ── Newborn assessment ── */

  openAssessment(): void {
    this.assessmentForm = { assessmentTime: this.nowLocalDatetime() };
    this.selectedFollowUps = new Set();
    this.showAssessmentModal.set(true);
  }

  closeAssessment(): void {
    this.showAssessmentModal.set(false);
  }

  toggleFollowUp(action: NewbornFollowUpAction): void {
    if (this.selectedFollowUps.has(action)) this.selectedFollowUps.delete(action);
    else this.selectedFollowUps.add(action);
  }

  submitAssessment(): void {
    const patient = this.patient();
    if (!patient) return;
    this.assessmentForm.recordedByStaffId = this.auth.getUserProfile()?.staffId ?? undefined;
    this.assessmentForm.followUpActions = [...this.selectedFollowUps];
    this.assessmentSaving.set(true);
    this.postpartumService.createNewbornAssessment(patient.id, this.assessmentForm).subscribe({
      next: (created) => {
        this.toast.success(this.translate.instant('POSTPARTUM.ASSESSMENT_SAVED'));
        this.assessmentSaving.set(false);
        this.closeAssessment();
        this.assessments.update((list) => [created, ...list]);
        for (const alert of created.alerts ?? []) {
          if (alert.severity === 'URGENT') this.toast.error(alert.message);
          else this.toast.info(alert.message);
        }
      },
      error: () => {
        this.toast.error(this.translate.instant('POSTPARTUM.ASSESSMENT_SAVE_ERROR'));
        this.assessmentSaving.set(false);
      },
    });
  }

  apgarSummary(a: NewbornAssessmentResponse): string {
    const parts = [a.apgarOneMinute, a.apgarFiveMinute, a.apgarTenMinute];
    return parts.map((v) => (v === null || v === undefined ? '—' : String(v))).join(' / ');
  }
}
