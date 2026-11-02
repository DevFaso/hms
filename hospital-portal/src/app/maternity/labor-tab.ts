import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  LaborService,
  LaborEpisodeResponse,
  LaborEpisodeRequest,
  PartographEntryResponse,
  PartographEntryRequest,
  DeliveryRecordResponse,
  DeliveryRecordRequest,
  MembraneStatus,
  LiquorColour,
  MouldingDegree,
  DeliveryMode,
  InfantSex,
  PerinealTear,
} from '../services/labor.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { labelledRefusalReason } from '../shared/refusal-reason';
import { FieldLimit, firstOutOfRange, labelKeysOf, rangeMessage } from '../shared/field-limits';
import { MeasurePipe } from '../shared/pipes/measure.pipe';
import { PartographChartComponent } from './partograph-chart.component';

/**
 * Labor & Delivery tab (P1 #6): start a labor episode, chart WHO partograph
 * timepoints against the alert/action lines, file the delivery record.
 */
import { fromServerDateTime, localInputInstant, withServerDateTimes } from '../shared/date-utils';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
/**
 * QA D808: the bounds PartographEntryRequestDTO puts on each number. The
 * server refused a dilation of 15 with a bare "≤ 10"; the form now refuses it
 * first and names the field.
 */
export const PARTOGRAPH_LIMITS: readonly FieldLimit[] = [
  { field: 'fetalHeartRateBpm', labelKey: 'LABOR.FHR', min: 0, max: 300 },
  { field: 'cervicalDilationCm', labelKey: 'LABOR.DILATION', min: 0, max: 10 },
  { field: 'descentFifths', labelKey: 'LABOR.DESCENT', min: 0, max: 5 },
  { field: 'contractionsPerTenMinutes', labelKey: 'LABOR.CONTRACTIONS_TEN', min: 0, max: 10 },
  { field: 'contractionDurationSeconds', labelKey: 'LABOR.CONTRACTION_SECONDS', min: 0, max: 600 },
  { field: 'oxytocinDropsPerMinute', labelKey: 'LABOR.OXYTOCIN_DROPS', min: 0, max: 120 },
  { field: 'pulseBpm', labelKey: 'LABOR.PULSE', min: 0, max: 300 },
  { field: 'systolicBpMmHg', labelKey: 'LABOR.SYSTOLIC', min: 0, max: 350 },
  { field: 'diastolicBpMmHg', labelKey: 'LABOR.DIASTOLIC', min: 0, max: 250 },
  { field: 'temperatureCelsius', labelKey: 'LABOR.TEMPERATURE', min: 25, max: 44 },
  { field: 'urineOutputMl', labelKey: 'LABOR.URINE_ML', min: 0 },
];

/** QA D808: the bounds DeliveryRecordRequestDTO puts on each number (APGAR 15 was sent). */
export const DELIVERY_LIMITS: readonly FieldLimit[] = [
  { field: 'numberOfInfants', labelKey: 'LABOR.NUMBER_OF_INFANTS', min: 1, max: 5 },
  { field: 'birthWeightGrams', labelKey: 'LABOR.BIRTH_WEIGHT', min: 200, max: 9000 },
  { field: 'apgarOneMinute', labelKey: 'LABOR.APGAR_ONE', min: 0, max: 10 },
  { field: 'apgarFiveMinute', labelKey: 'LABOR.APGAR_FIVE', min: 0, max: 10 },
  { field: 'estimatedBloodLossMl', labelKey: 'LABOR.BLOOD_LOSS', min: 0, max: 5000 },
];

/** QA D809: the bounds LaborEpisodeRequestDTO puts on the admission's numbers. */
export const EPISODE_LIMITS: readonly FieldLimit[] = [
  { field: 'gestationalAgeWeeks', labelKey: 'MATERNITY.GESTATIONAL_AGE', min: 20, max: 45 },
  // A woman admitted in labour is pregnant: G >= 1.
  { field: 'gravida', labelKey: 'MATERNITY.GRAVIDA', min: 1 },
  { field: 'para', labelKey: 'MATERNITY.PARA', min: 0 },
];

const PARTOGRAPH_LABELS: Record<string, string> = {
  ...labelKeysOf(PARTOGRAPH_LIMITS),
  observationTime: 'LABOR.TIME',
};
const DELIVERY_LABELS: Record<string, string> = {
  ...labelKeysOf(DELIVERY_LIMITS),
  birthDateTime: 'LABOR.BIRTH_TIME',
  placentaDeliveredAt: 'LABOR.PLACENTA_TIME',
};
const EPISODE_LABELS: Record<string, string> = {
  ...labelKeysOf(EPISODE_LIMITS),
  laborOnsetAt: 'LABOR.ONSET_TIME',
};

@Component({
  selector: 'app-labor-tab',
  standalone: true,
  imports: [
    SubmitGuardDirective,
    ModalDialogDirective,
    ServerTimePipe,
    MeasurePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    PatientPickerComponent,
    PartographChartComponent,
  ],
  templateUrl: './labor-tab.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LaborTabComponent {
  private readonly laborService = inject(LaborService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  patient = signal<PatientResponse | null>(null);
  episodes = signal<LaborEpisodeResponse[]>([]);
  episodesLoading = signal(false);
  entries = signal<PartographEntryResponse[]>([]);
  delivery = signal<DeliveryRecordResponse | null>(null);
  saving = signal(false);

  /** Newest ACTIVE episode, if any. */
  readonly activeEpisode = computed(
    () => this.episodes().find((episode) => episode.status === 'ACTIVE') ?? null,
  );
  /** Newest episode of any status — drives the delivered-summary view. */
  readonly latestEpisode = computed(() => this.episodes()[0] ?? null);

  /* ── Forms ─────────────────────────────────────────────── */
  startFormOpen = signal(false);
  startForm: LaborEpisodeRequest = {};
  entryFormOpen = signal(false);
  entryForm: PartographEntryRequest = {};
  deliveryFormOpen = signal(false);
  deliveryForm: Partial<DeliveryRecordRequest> = { liveBirth: true, numberOfInfants: 1 };

  readonly membraneStatuses: MembraneStatus[] = [
    'INTACT',
    'SPONTANEOUS_RUPTURE',
    'ARTIFICIAL_RUPTURE',
  ];
  readonly liquorColours: LiquorColour[] = [
    'MEMBRANES_INTACT',
    'CLEAR',
    'MECONIUM_STAINED',
    'BLOOD_STAINED',
    'ABSENT',
  ];
  readonly mouldingDegrees: MouldingDegree[] = ['NONE', 'PLUS_ONE', 'PLUS_TWO', 'PLUS_THREE'];
  readonly deliveryModes: DeliveryMode[] = [
    'SPONTANEOUS_VAGINAL',
    'VACUUM_EXTRACTION',
    'FORCEPS',
    'CAESAREAN_ELECTIVE',
    'CAESAREAN_EMERGENCY',
    'ASSISTED_BREECH',
  ];
  readonly infantSexes: InfantSex[] = ['MALE', 'FEMALE', 'UNDETERMINED'];
  readonly perinealTears: PerinealTear[] = [
    'NONE',
    'FIRST_DEGREE',
    'SECOND_DEGREE',
    'THIRD_DEGREE',
    'FOURTH_DEGREE',
    'EPISIOTOMY',
  ];

  onPatientChange(patient: PatientResponse | null): void {
    this.patient.set(patient);
    this.episodes.set([]);
    this.entries.set([]);
    this.delivery.set(null);
    this.startFormOpen.set(false);
    this.deliveryFormOpen.set(false);
    if (patient) {
      this.loadEpisodes(patient.id);
    }
  }

  private loadEpisodes(patientId: string): void {
    this.episodesLoading.set(true);
    this.laborService.episodes(patientId).subscribe({
      next: (episodes) => {
        this.episodes.set(episodes);
        this.episodesLoading.set(false);
        const current = episodes.find((episode) => episode.status === 'ACTIVE') ?? episodes[0];
        if (current) {
          this.loadEntries(patientId, current.id);
          if (current.deliveryRecorded) {
            this.loadDelivery(patientId, current.id);
          }
        }
      },
      error: () => {
        this.toast.error(this.translate.instant('LABOR.LOAD_ERROR'));
        this.episodesLoading.set(false);
      },
    });
  }

  private loadEntries(patientId: string, episodeId: string): void {
    this.laborService.entries(patientId, episodeId).subscribe({
      next: (entries) => this.entries.set(entries),
      error: () => this.toast.error(this.translate.instant('LABOR.LOAD_ERROR')),
    });
  }

  private loadDelivery(patientId: string, episodeId: string): void {
    this.laborService.delivery(patientId, episodeId).subscribe({
      next: (delivery) => this.delivery.set(delivery),
      error: () => this.delivery.set(null),
    });
  }

  /* ── Start episode ─────────────────────────────────────── */

  openStartForm(): void {
    this.startForm = {};
    this.startFormOpen.set(true);
  }

  cancelStart(): void {
    this.startFormOpen.set(false);
  }

  submitStart(): void {
    const patient = this.patient();
    // QA D295: an episode needs its onset time; the button is disabled without it.
    if (!patient || !this.startForm.laborOnsetAt) return;
    const outOfRange = firstOutOfRange(this.startForm, EPISODE_LIMITS);
    if (outOfRange) {
      this.toast.error(rangeMessage(outOfRange, this.translate));
      return;
    }
    // QA D809: G1 P3 was saved. P counts births, each of which ended a pregnancy counted in G.
    const { gravida, para } = this.startForm;
    if (
      gravida !== undefined &&
      gravida !== null &&
      para !== undefined &&
      para !== null &&
      para > gravida
    ) {
      this.toast.error(this.translate.instant('MATERNITY.PARA_ABOVE_GRAVIDA'));
      return;
    }
    this.saving.set(true);
    this.laborService
      .startEpisode(
        patient.id,
        withServerDateTimes(this.startForm, ['laborOnsetAt', 'admittedAt', 'membraneRuptureAt']),
      )
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('LABOR.EPISODE_STARTED'));
          this.saving.set(false);
          this.startFormOpen.set(false);
          this.loadEpisodes(patient.id);
        },
        error: (err: unknown) => {
          // QA D214: the server's reason (e.g. a recent delivery needs an
          // override) is shown, not hidden behind a generic failure; QA D746:
          // a field reason (an admission in the future) included.
          this.toast.error(
            labelledRefusalReason(err, this.translate, EPISODE_LABELS) ??
              this.translate.instant('LABOR.EPISODE_START_ERROR'),
          );
          this.saving.set(false);
        },
      });
  }

  /* ── Partograph entries ────────────────────────────────── */

  submitEntry(): void {
    const patient = this.patient();
    const episode = this.activeEpisode();
    if (!patient || !episode) return;
    const form = this.entryForm;
    const hasObservation = [
      form.fetalHeartRateBpm,
      form.liquorColour,
      form.mouldingDegree,
      form.cervicalDilationCm,
      form.descentFifths,
      form.contractionsPerTenMinutes,
      form.pulseBpm,
      form.systolicBpMmHg,
      form.temperatureCelsius,
      form.oxytocinDropsPerMinute,
      form.drugsGiven,
      form.notes,
    ].some((value) => value !== undefined && value !== null && value !== ('' as unknown));
    if (!hasObservation) {
      this.toast.error(this.translate.instant('LABOR.ENTRY_REQUIRED'));
      return;
    }
    const outOfRange = firstOutOfRange(form, PARTOGRAPH_LIMITS);
    if (outOfRange) {
      this.toast.error(rangeMessage(outOfRange, this.translate));
      return;
    }
    this.saving.set(true);
    this.laborService
      .addEntry(patient.id, episode.id, withServerDateTimes(form, ['observationTime']))
      .subscribe({
        next: (entry) => {
          this.toast.success(this.translate.instant('LABOR.ENTRY_SAVED'));
          for (const alert of entry.alerts ?? []) {
            if (alert.severity === 'URGENT') {
              this.toast.error(alert.message);
            } else if (alert.severity === 'CAUTION') {
              this.toast.info(alert.message);
            }
          }
          this.saving.set(false);
          this.entryForm = {};
          this.entryFormOpen.set(false);
          this.loadEpisodes(patient.id);
        },
        error: (err: unknown) => {
          this.toast.error(
            labelledRefusalReason(err, this.translate, PARTOGRAPH_LABELS) ??
              this.translate.instant('LABOR.ENTRY_SAVE_ERROR'),
          );
          this.saving.set(false);
        },
      });
  }

  /* ── Delivery ──────────────────────────────────────────── */

  openDeliveryForm(): void {
    this.deliveryForm = { liveBirth: true, numberOfInfants: 1 };
    this.deliveryFormOpen.set(true);
  }

  cancelDelivery(): void {
    this.deliveryFormOpen.set(false);
  }

  submitDelivery(): void {
    const patient = this.patient();
    const episode = this.activeEpisode();
    if (!patient || !episode) return;
    if (!this.deliveryForm.deliveryMode) {
      this.toast.error(this.translate.instant('LABOR.DELIVERY_MODE_REQUIRED'));
      return;
    }
    const problem = this.deliveryProblem();
    if (problem) {
      this.toast.error(problem);
      return;
    }
    this.saving.set(true);
    this.laborService
      .recordDelivery(
        patient.id,
        episode.id,
        withServerDateTimes(this.deliveryForm as DeliveryRecordRequest, [
          'birthDateTime',
          'placentaDeliveredAt',
        ]),
      )
      .subscribe({
        next: (delivery) => {
          this.toast.success(this.translate.instant('LABOR.DELIVERY_SAVED'));
          for (const alert of delivery.alerts ?? []) {
            if (alert.severity === 'URGENT') {
              this.toast.error(alert.message);
            }
          }
          this.saving.set(false);
          this.deliveryFormOpen.set(false);
          this.loadEpisodes(patient.id);
        },
        error: (err: unknown) => {
          this.toast.error(
            labelledRefusalReason(err, this.translate, DELIVERY_LABELS) ??
              this.translate.instant('LABOR.DELIVERY_SAVE_ERROR'),
          );
          this.saving.set(false);
        },
      });
  }

  /**
   * QA D806/D808: what is wrong with the delivery form before it is sent, or
   * null. A number outside the server's bounds; a birth earlier than an
   * observation already charted on the partograph; a placenta delivered
   * before the birth (no birth time typed means the birth is recorded now).
   * The server refuses the same, on the same fields.
   */
  private deliveryProblem(): string | null {
    const outOfRange = firstOutOfRange(this.deliveryForm, DELIVERY_LIMITS);
    if (outOfRange) return rangeMessage(outOfRange, this.translate);
    const typedBirth = localInputInstant(this.deliveryForm.birthDateTime);
    const birth = typedBirth ?? new Date();
    const lastObservation = this.entries()
      .map((entry) => fromServerDateTime(entry.observationTime))
      .filter((when): when is Date => when !== null)
      .reduce<Date | null>((latest, when) => (!latest || when > latest ? when : latest), null);
    if (typedBirth && lastObservation && typedBirth < lastObservation) {
      return this.translate.instant('LABOR.BIRTH_BEFORE_LAST_ENTRY');
    }
    const placenta = localInputInstant(this.deliveryForm.placentaDeliveredAt);
    if (placenta && placenta < birth) {
      return this.translate.instant('LABOR.PLACENTA_BEFORE_BIRTH');
    }
    return null;
  }

  /* ── Display helpers ───────────────────────────────────── */

  entryAlertClass(entry: PartographEntryResponse): string {
    if (entry.alerts?.some((alert) => alert.severity === 'URGENT')) return 'labor-row-urgent';
    if (entry.alerts?.some((alert) => alert.severity === 'CAUTION')) return 'labor-row-caution';
    return '';
  }
}
