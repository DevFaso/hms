import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
import { DeliveryRecordResponse, LaborService } from '../services/labor.service';
import {
  ProAnswers,
  ProInstrumentView,
  ProResponse,
  ProScreeningService,
} from '../services/pro-screening.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import {
  ProInstrumentFormComponent,
  unansweredItems,
} from '../shared/pro-instrument-form/pro-instrument-form.component';
import { nowLocalDatetime } from '../shared/date-utils';

/** The instrument the postpartum module screens with when the plan does not say. */
const DEFAULT_SCREENING_INSTRUMENT = 'EPDS';

@Component({
  selector: 'app-postpartum-tab',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    PatientPickerComponent,
    ProInstrumentFormComponent,
  ],
  templateUrl: './postpartum-tab.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PostpartumTabComponent {
  private readonly postpartumService = inject(PostpartumService);
  private readonly laborService = inject(LaborService);
  private readonly screeningService = inject(ProScreeningService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  patient = signal<PatientResponse | null>(null);
  section = signal<'mother' | 'newborn'>('mother');

  /**
   * The mother's recorded deliveries, offered as the link target when filing a
   * newborn assessment. PR #450 built the deliveryRecordId column, FK and
   * validation end to end — and nothing in any UI ever SET it, so the link
   * stayed persistence-only until the 2026-08-21 reassessment. Best-effort:
   * a mother with no L&D episode simply gets no selector.
   */
  deliveries = signal<DeliveryRecordResponse[]>([]);

  schedule = signal<PostpartumSchedule | null>(null);
  observations = signal<PostpartumObservationResponse[]>([]);
  observationsLoading = signal(false);
  observationsError = signal(false);
  assessments = signal<NewbornAssessmentResponse[]>([]);
  assessmentsLoading = signal(false);
  assessmentsError = signal(false);

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

  /* ── Mental-health screening (Tier 2 item 47) ── */
  screenings = signal<ProResponse[]>([]);
  screeningsLoading = signal(false);
  screeningsError = signal(false);
  /** The instrument this plan screens with; the schedule says, else EPDS. */
  screeningCode = computed(
    () => this.schedule()?.screening?.instrumentCode ?? DEFAULT_SCREENING_INSTRUMENT,
  );
  /**
   * Until the validated text is loaded (SUPER_ADMIN import), there is nothing
   * to administer — the button stays off rather than opening an empty modal.
   * An older schedule without the hook is treated as available so the modal
   * can still explain itself.
   */
  screeningAvailable = computed(() => this.schedule()?.screening?.instrumentAvailable ?? true);

  showScreeningModal = signal(false);
  screeningInstrument = signal<ProInstrumentView | null>(null);
  screeningInstrumentLoading = signal(false);
  screeningInstrumentError = signal(false);
  screeningAnswers = signal<ProAnswers>({});
  screeningSaving = signal(false);
  screeningLanguage = '';
  screeningNotes = '';
  screeningAdministeredAt = '';

  showAckModal = signal(false);
  ackTarget = signal<ProResponse | null>(null);
  ackSaving = signal(false);
  ackNote = '';

  onPatientPicked(p: PatientResponse | null): void {
    this.patient.set(p);
    this.schedule.set(null);
    this.observations.set([]);
    this.assessments.set([]);
    this.screenings.set([]);
    if (p) this.load();
  }

  setSection(section: 'mother' | 'newborn'): void {
    this.section.set(section);
  }

  load(): void {
    const patient = this.patient();
    if (!patient) return;
    this.loadSchedule(patient.id);
    this.loadScreenings(patient.id);
    // Mother and newborn sections track their own loading/error state — a
    // shared flag let the newborn list show "no assessments" while its
    // request was still in flight (or had silently failed).
    this.observationsLoading.set(true);
    this.observationsError.set(false);
    this.postpartumService.recentObservations(patient.id, 20).subscribe({
      next: (list) => {
        this.observations.set(list ?? []);
        this.observationsLoading.set(false);
      },
      error: () => {
        this.observations.set([]);
        this.observationsLoading.set(false);
        this.observationsError.set(true);
      },
    });
    this.assessmentsLoading.set(true);
    this.assessmentsError.set(false);
    this.postpartumService.recentNewbornAssessments(patient.id, 20).subscribe({
      next: (list) => {
        this.assessments.set(list ?? []);
        this.assessmentsLoading.set(false);
      },
      error: () => {
        this.assessments.set([]);
        this.assessmentsLoading.set(false);
        this.assessmentsError.set(true);
      },
    });
    this.loadDeliveries(patient.id);
  }

  private loadSchedule(patientId: string): void {
    this.postpartumService.schedule(patientId).subscribe({
      next: (schedule) => {
        if (this.patient()?.id === patientId) this.schedule.set(schedule);
      },
      error: () => this.schedule.set(null),
    });
  }

  private loadScreenings(patientId: string): void {
    this.screeningsLoading.set(true);
    this.screeningsError.set(false);
    this.screeningService.history(patientId, this.screeningCode(), 20).subscribe({
      next: (list) => {
        if (this.patient()?.id !== patientId) return;
        this.screenings.set(list ?? []);
        this.screeningsLoading.set(false);
      },
      error: () => {
        this.screenings.set([]);
        this.screeningsLoading.set(false);
        this.screeningsError.set(true);
      },
    });
  }

  /**
   * Delivery records hang off labor episodes, so this walks episodes first and
   * fetches the record for each that has one. Errors are swallowed per episode:
   * the selector is an enrichment, and a labor-module hiccup must not block
   * filing a newborn assessment.
   */
  private loadDeliveries(patientId: string): void {
    this.deliveries.set([]);
    this.laborService.episodes(patientId, 10).subscribe({
      next: (episodes) => {
        for (const episode of episodes ?? []) {
          if (!episode.deliveryRecorded) continue;
          this.laborService.delivery(patientId, episode.id).subscribe({
            // Guard against a stale response: re-picking a patient while the
            // previous patient's requests are in flight must not put another
            // mother's delivery in the selector — the hospital-only backend
            // check would accept the link, and there is no edit path to undo
            // it.
            next: (record) => {
              if (this.patient()?.id !== patientId) {
                return;
              }
              this.deliveries.update((list) => [...list, record]);
            },
            error: () => undefined,
          });
        }
      },
      error: () => undefined,
    });
  }

  /** "12 Aug 2026, vaginal" — enough to tell twins' episodes apart. */
  deliveryLabel(d: DeliveryRecordResponse): string {
    const when = d.birthDateTime ? new Date(d.birthDateTime).toLocaleDateString() : '';
    return `${when} — ${d.deliveryMode ?? ''}`.trim();
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

  /* ── Observation ── */

  openObservation(): void {
    this.observationForm = { observationTime: nowLocalDatetime() };
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
    this.assessmentForm = { assessmentTime: nowLocalDatetime() };
    // One recorded delivery is the overwhelmingly common case; preselect it so
    // the linkage happens by default and a clinician only touches the selector
    // for twins or a transferred-in newborn (where "none" is correct).
    const deliveries = this.deliveries();
    if (deliveries.length === 1) {
      this.assessmentForm.deliveryRecordId = deliveries[0].id;
    }
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

  /* ── Mental-health screening ── */

  retryScreenings(): void {
    const patient = this.patient();
    if (patient) this.loadScreenings(patient.id);
  }

  /** A safety-item answer nobody on the care team has yet signed off. */
  needsAcknowledgement(r: ProResponse): boolean {
    return r.criticalItemPositive && !r.acknowledgedAt;
  }

  openScreening(): void {
    this.screeningAnswers.set({});
    this.screeningNotes = '';
    this.screeningAdministeredAt = nowLocalDatetime();
    this.screeningLanguage = this.translate.currentLang || '';
    this.showScreeningModal.set(true);
    this.loadScreeningInstrument(this.screeningLanguage);
  }

  closeScreening(): void {
    this.showScreeningModal.set(false);
  }

  /**
   * Same instrument, other loaded language. Answers survive the switch: the
   * structure is identical in every language, so item 3 / option 2 means the
   * same thing whichever wording the mother was read.
   */
  changeScreeningLanguage(language: string): void {
    this.screeningLanguage = language;
    this.loadScreeningInstrument(language);
  }

  private loadScreeningInstrument(language: string): void {
    this.screeningInstrumentLoading.set(true);
    this.screeningInstrumentError.set(false);
    this.screeningService.instrument(this.screeningCode(), language || undefined).subscribe({
      next: (view) => {
        this.screeningInstrument.set(view);
        // The server may have fallen back to English; show what was served.
        this.screeningLanguage = view.language;
        this.screeningInstrumentLoading.set(false);
      },
      error: () => {
        this.screeningInstrument.set(null);
        this.screeningInstrumentLoading.set(false);
        this.screeningInstrumentError.set(true);
      },
    });
  }

  screeningUnanswered(): number[] {
    const instrument = this.screeningInstrument();
    return instrument ? unansweredItems(instrument, this.screeningAnswers()) : [];
  }

  submitScreening(): void {
    const patient = this.patient();
    const instrument = this.screeningInstrument();
    if (!patient || !instrument) return;
    // Every item or nothing: a partial EPDS has no validated cutoff, and the
    // server would refuse it anyway — better to say which ones are missing.
    const missing = this.screeningUnanswered();
    if (missing.length > 0) {
      this.toast.warning(this.translate.instant('PRO.INCOMPLETE', { items: missing.join(', ') }));
      return;
    }
    this.screeningSaving.set(true);
    this.screeningService
      .record(patient.id, {
        instrumentCode: instrument.code,
        language: instrument.language,
        answers: this.screeningAnswers(),
        administeredAt: this.screeningAdministeredAt || undefined,
        notes: this.screeningNotes.trim() || undefined,
      })
      .subscribe({
        next: (created) => {
          this.screeningSaving.set(false);
          this.closeScreening();
          this.screenings.update((list) => [created, ...list]);
          this.loadSchedule(patient.id);
          if (created.criticalItemPositive) {
            this.toast.error(this.translate.instant('PRO.SAVED_CRITICAL'));
          } else if (created.screenPositive) {
            this.toast.warning(this.translate.instant('PRO.SAVED_POSITIVE'));
          } else {
            this.toast.success(this.translate.instant('PRO.SAVED'));
          }
        },
        error: () => {
          this.toast.error(this.translate.instant('PRO.SAVE_ERROR'));
          this.screeningSaving.set(false);
        },
      });
  }

  openAcknowledge(r: ProResponse): void {
    this.ackTarget.set(r);
    this.ackNote = '';
    this.showAckModal.set(true);
  }

  closeAcknowledge(): void {
    this.showAckModal.set(false);
    this.ackTarget.set(null);
  }

  submitAcknowledge(): void {
    const patient = this.patient();
    const target = this.ackTarget();
    if (!patient || !target) return;
    this.ackSaving.set(true);
    this.screeningService.acknowledge(patient.id, target.id, this.ackNote).subscribe({
      next: (updated) => {
        this.ackSaving.set(false);
        this.closeAcknowledge();
        this.screenings.update((list) => list.map((r) => (r.id === updated.id ? updated : r)));
        this.loadSchedule(patient.id);
        this.toast.success(this.translate.instant('PRO.ACKNOWLEDGED'));
      },
      error: () => {
        // "Already acknowledged by a colleague" and "reload and retry" are the
        // usual refusals; both mean the row on screen is stale, so re-fetch.
        this.toast.error(this.translate.instant('PRO.ACKNOWLEDGE_ERROR'));
        this.ackSaving.set(false);
        this.loadScreenings(patient.id);
      },
    });
  }
}
