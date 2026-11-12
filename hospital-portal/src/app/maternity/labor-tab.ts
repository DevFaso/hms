import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  signal,
} from '@angular/core';
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
  LaborTransferRequest,
} from '../services/labor.service';
import { HospitalService, HospitalDirectoryEntry } from '../services/hospital.service';
import { actionRoles } from '../core/action-permissions';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { labelledRefusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { FieldLimit, firstOutOfRange, labelKeysOf, rangeMessage } from '../shared/field-limits';
import { MeasurePipe } from '../shared/pipes/measure.pipe';
import { bloodPressureReversed } from '../shared/vital-sign-limits';
import { PartographChartComponent } from './partograph-chart.component';

/**
 * Labor & Delivery tab (P1 #6): start a labor episode, chart WHO partograph
 * timepoints against the alert/action lines, file the delivery record.
 */
import { fromServerDateTime, localInputInstant, withServerDateTimes } from '../shared/date-utils';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { ModalDialogDirective } from '../shared/a11y/modal-dialog.directive';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { FieldRefusal, refuseFields } from '../shared/forms/invalid-fields';
import { DateTimePipe } from '../shared/pipes/date-time.pipe';
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
  // QA D1424: VitalSignLimits, as on every other vitals form.
  { field: 'pulseBpm', labelKey: 'LABOR.PULSE', min: 20, max: 300 },
  { field: 'systolicBpMmHg', labelKey: 'LABOR.SYSTOLIC', min: 40, max: 300 },
  { field: 'diastolicBpMmHg', labelKey: 'LABOR.DIASTOLIC', min: 20, max: 200 },
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
  recentDeliveryOverrideReason: 'LABOR.RECENT_DELIVERY_OVERRIDE',
};
const EPISODE_LABELS: Record<string, string> = {
  ...labelKeysOf(EPISODE_LIMITS),
  laborOnsetAt: 'LABOR.ONSET_TIME',
  recentDeliveryOverrideReason: 'LABOR.RECENT_DELIVERY_OVERRIDE',
};

/** QA D1306 (round 28): the template id of each field a form's check may refuse. */
const EPISODE_FIELD_IDS: Readonly<Record<string, string>> = {
  gestationalAgeWeeks: 'labor-ga',
  gravida: 'labor-gravida',
  para: 'labor-para',
  laborOnsetAt: 'labor-onset',
  recentDeliveryOverrideReason: 'labor-override',
};
const PARTOGRAPH_FIELD_IDS: Readonly<Record<string, string>> = {
  fetalHeartRateBpm: 'pe-fhr',
  cervicalDilationCm: 'pe-dilation',
  descentFifths: 'pe-descent',
  contractionsPerTenMinutes: 'pe-contractions',
  contractionDurationSeconds: 'pe-duration',
  oxytocinDropsPerMinute: 'pe-oxytocin',
  pulseBpm: 'pe-pulse',
  systolicBpMmHg: 'pe-sys',
  diastolicBpMmHg: 'pe-dia',
  temperatureCelsius: 'pe-temp',
  urineOutputMl: 'pe-urine',
};
/** QA D1446: the template id of each field the server may name under a partograph refusal. */
const PARTOGRAPH_SERVER_FIELD_IDS: Readonly<Record<string, string>> = {
  ...PARTOGRAPH_FIELD_IDS,
  observationTime: 'pe-time',
};
const DELIVERY_MODE_ID = 'dl-mode';
const DELIVERY_BIRTH_TIME_ID = 'dl-time';
const DELIVERY_PLACENTA_TIME_ID = 'dl-placenta-time';
const DELIVERY_FIELD_IDS: Readonly<Record<string, string>> = {
  numberOfInfants: 'dl-infants',
  birthWeightGrams: 'dl-weight',
  apgarOneMinute: 'dl-apgar1',
  apgarFiveMinute: 'dl-apgar5',
  estimatedBloodLossMl: 'dl-ebl',
  recentDeliveryOverrideReason: 'dl-override',
};

/**
 * QA D1339 (D1272 / D1256 class, round 29): the server refuses a labour, or a
 * birth, within this many days of another recorded birth unless an override
 * reason is given (`LaborServiceImpl.MIN_DAYS_BETWEEN_DELIVERIES`; the hint
 * says "20 weeks"). The override field is then required, starred and marked.
 */
const RECENT_DELIVERY_DAYS = 140;
const DAY_MS = 24 * 60 * 60 * 1000;
const EPISODE_IDS = Object.values(EPISODE_FIELD_IDS);
/** The template id of each field the server may name under a delivery refusal's `fieldErrors`. */
const DELIVERY_SERVER_FIELD_IDS: Readonly<Record<string, string>> = {
  ...DELIVERY_FIELD_IDS,
  deliveryMode: DELIVERY_MODE_ID,
  birthDateTime: DELIVERY_BIRTH_TIME_ID,
  placentaDeliveredAt: DELIVERY_PLACENTA_TIME_ID,
};
const PARTOGRAPH_IDS = Object.values(PARTOGRAPH_SERVER_FIELD_IDS);

/**
 * Labour transfer (provisional rule, 2026-11-12, pending clinical approval):
 * the template id of each field the transfer and arrival dialogs, and the
 * server's refusals of them, may mark.
 */
const TRANSFER_FIELD_IDS: Readonly<Record<string, string>> = {
  destinationHospitalId: 'lt-destination',
  reason: 'lt-reason',
};
const TRANSFER_IDS = Object.values(TRANSFER_FIELD_IDS);
const TRANSFER_LABELS: Record<string, string> = {
  destinationHospitalId: 'LABOR.TRANSFER.DESTINATION',
  reason: 'LABOR.TRANSFER.REASON',
};
const ARRIVAL_FIELD_IDS: Readonly<Record<string, string>> = { reason: 'la-reason' };
const ARRIVAL_IDS = Object.values(ARRIVAL_FIELD_IDS);
const ARRIVAL_LABELS: Record<string, string> = { reason: 'LABOR.TRANSFER.REASON' };
/** LaborTransferRequestDTO's bound on the reason. */
export const TRANSFER_REASON_MAX = 2000;
const DELIVERY_IDS = [
  DELIVERY_BIRTH_TIME_ID,
  DELIVERY_MODE_ID,
  ...Object.values(DELIVERY_FIELD_IDS),
  DELIVERY_PLACENTA_TIME_ID,
];

@Component({
  selector: 'app-labor-tab',
  standalone: true,
  imports: [
    DateTimePipe,
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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly laborService = inject(LaborService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly roleContext = inject(RoleContextService);
  private readonly hospitalService = inject(HospitalService);

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
  /**
   * QA D1631: the active labour is another hospital's (a shared patient). It
   * is shown read only, named, with no record button: the server refuses
   * every write on it, and a second labour while it is open.
   */
  readonly activeEpisodeForeign = computed(() => {
    const episode = this.activeEpisode();
    return !!episode && this.isForeign(episode);
  });

  /**
   * QA D1631, the shared-patient read-only idiom (#1103): a record another
   * hospital owns is read only here. Judged against the hospital the requests
   * are pinned to; in global view (no scope) nothing is "elsewhere".
   */
  isForeign(record: { hospitalId?: string | null }): boolean {
    const scope = this.roleContext.effectiveHospitalIdForRequest();
    return !!record.hospitalId && !!scope && record.hospitalId !== scope;
  }

  /* ── Labour transfer (provisional rule, 2026-11-12) ───────── */

  /**
   * "Transférer": the active labour is this hospital's, and the caller may
   * admit a labour (a doctor; a midwife, #1102). The server also requires the
   * role at this hospital.
   */
  readonly canTransfer = computed(
    () =>
      !!this.activeEpisode() &&
      !this.activeEpisodeForeign() &&
      this.roleContext.hasAnyActiveRole(actionRoles('laborTransfer')),
  );
  /** "Arrivée sans transmission": the active labour is another hospital's, shown read only here. */
  readonly canRecordArrival = computed(
    () =>
      this.activeEpisodeForeign() &&
      this.roleContext.hasAnyActiveRole(actionRoles('laborArrivalWithoutHandover')),
  );
  /** The episode the active one continues (a transfer or an arrival), when this hospital may read it. */
  readonly previousEpisode = computed(() => {
    const from = this.activeEpisode()?.transferredFromEpisodeId;
    return from ? (this.episodes().find((episode) => episode.id === from) ?? null) : null;
  });
  /** The partograph of the episode the active one continues: read only. */
  previousEntries = signal<PartographEntryResponse[]>([]);
  /** The newest episode when it closed by a transfer or an arrival elsewhere, and none is active. */
  readonly closedByTransfer = computed(() => {
    const latest = this.latestEpisode();
    return !this.activeEpisode() &&
      latest &&
      (latest.status === 'TRANSFERRED_OUT' || latest.status === 'LEFT_WITHOUT_HANDOVER')
      ? latest
      : null;
  });

  transferOpen = signal(false);
  transferForm: Partial<LaborTransferRequest> = {};
  destinations = signal<HospitalDirectoryEntry[]>([]);
  arrivalOpen = signal(false);
  arrivalReason = '';
  readonly transferReasonMax = TRANSFER_REASON_MAX;

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
    this.previousEntries.set([]);
    this.transferOpen.set(false);
    this.arrivalOpen.set(false);
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
        this.loadPreviousEntries(patientId, current?.transferredFromEpisodeId ?? null);
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

  /**
   * Labour transfer: the partograph charted before the transfer, at the other
   * hospital, read only. Not readable here (the sharing rule) leaves it empty.
   */
  private loadPreviousEntries(patientId: string, episodeId: string | null): void {
    this.previousEntries.set([]);
    if (!episodeId) return;
    this.laborService.entries(patientId, episodeId).subscribe({
      next: (entries) => this.previousEntries.set(entries),
      error: () => this.previousEntries.set([]),
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
      const message = rangeMessage(outOfRange, this.translate);
      // QA D1306 (round 28): the field is marked, not only a toast.
      refuseFields(
        this.host.nativeElement,
        [EPISODE_FIELD_IDS[outOfRange.field]],
        EPISODE_IDS,
        message,
      );
      this.toast.error(message);
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
      const message = this.translate.instant('MATERNITY.PARA_ABOVE_GRAVIDA');
      refuseFields(this.host.nativeElement, [EPISODE_FIELD_IDS['para']], EPISODE_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], EPISODE_IDS, '');
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
          // QA D1339: the fields the refusal names are marked, each with its reason.
          this.markServerRefusals(err, EPISODE_FIELD_IDS, EPISODE_IDS);
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
      const message = this.translate.instant('LABOR.ENTRY_REQUIRED');
      // At least one observation: the first one (the fetal heart rate) is marked.
      refuseFields(
        this.host.nativeElement,
        [PARTOGRAPH_FIELD_IDS['fetalHeartRateBpm']],
        PARTOGRAPH_IDS,
        message,
      );
      this.toast.error(message);
      return;
    }
    const outOfRange = firstOutOfRange(form, PARTOGRAPH_LIMITS);
    if (outOfRange) {
      const message = rangeMessage(outOfRange, this.translate);
      // QA D1306 (round 28): the field is marked, not only a toast.
      refuseFields(
        this.host.nativeElement,
        [PARTOGRAPH_FIELD_IDS[outOfRange.field]],
        PARTOGRAPH_IDS,
        message,
      );
      this.toast.error(message);
      return;
    }
    if (bloodPressureReversed(form.systolicBpMmHg, form.diastolicBpMmHg)) {
      // QA D1424: 70/120 is a swapped entry; it never reaches the hypertension alert.
      const message = this.translate.instant('VITAL_LIMITS.BP_ORDER');
      refuseFields(
        this.host.nativeElement,
        [PARTOGRAPH_FIELD_IDS['diastolicBpMmHg']],
        PARTOGRAPH_IDS,
        message,
      );
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], PARTOGRAPH_IDS, '');
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
          // QA D1446: the fields the refusal names are marked, as on the delivery form.
          this.markServerRefusals(err, PARTOGRAPH_SERVER_FIELD_IDS, PARTOGRAPH_IDS);
          this.toast.error(
            labelledRefusalReason(err, this.translate, PARTOGRAPH_LABELS) ??
              this.translate.instant('LABOR.ENTRY_SAVE_ERROR'),
          );
          this.saving.set(false);
        },
      });
  }

  /* ── Recent-delivery override (QA D1339) ─────────────────── */

  /**
   * Whether another of this patient's labours ended in a birth within
   * {@link RECENT_DELIVERY_DAYS} days before `at`. Read from the labour's
   * admission, which precedes its birth: an admission inside the window puts
   * the birth inside it too, so the field is never required when the server
   * would not ask for it (a birth just inside the window after an admission
   * just outside it is refused by the server and marked from its fieldErrors).
   */
  private recentDeliveryBefore(at: Date, excludeEpisodeId: string | null): boolean {
    const from = at.getTime() - RECENT_DELIVERY_DAYS * DAY_MS;
    return this.episodes().some((episode) => {
      if (!episode.deliveryRecorded || episode.id === excludeEpisodeId) return false;
      const admitted = fromServerDateTime(episode.admittedAt)?.getTime();
      return admitted !== undefined && admitted >= from && admitted <= at.getTime();
    });
  }

  /** The start form's override reason is required: a birth was recorded recently. */
  startOverrideRequired(): boolean {
    return this.recentDeliveryBefore(new Date(), null);
  }

  /** The delivery form's override reason is required: another birth is recent. */
  deliveryOverrideRequired(): boolean {
    const at = localInputInstant(this.deliveryForm.birthDateTime) ?? new Date();
    return this.recentDeliveryBefore(at, this.activeEpisode()?.id ?? null);
  }

  /** Marks each field a refusal names under `fieldErrors` with the server's reason. */
  private markServerRefusals(
    err: unknown,
    idByField: Readonly<Record<string, string>>,
    all: readonly string[],
  ): void {
    const refused = refusedFieldReasons(err, idByField);
    if (refused.length === 0) return;
    refuseFields(
      this.host.nativeElement,
      refused.map(([id, message]) => ({ id, message })),
      all,
    );
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
      const message = this.translate.instant('LABOR.DELIVERY_MODE_REQUIRED');
      refuseFields(this.host.nativeElement, [DELIVERY_MODE_ID], DELIVERY_IDS, message);
      this.toast.error(message);
      return;
    }
    const problem = this.deliveryProblem();
    if (problem) {
      // QA D1306 (round 28): the field is marked, not only a toast.
      refuseFields(this.host.nativeElement, [problem], DELIVERY_IDS);
      this.toast.error(problem.message);
      return;
    }
    refuseFields(this.host.nativeElement, [], DELIVERY_IDS, '');
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
          // QA D1339: the fields the refusal names are marked, each with its reason.
          this.markServerRefusals(err, DELIVERY_SERVER_FIELD_IDS, DELIVERY_IDS);
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
   * The server refuses the same, on the same fields. Returns the field to mark with its reason.
   */
  private deliveryProblem(): FieldRefusal | null {
    const outOfRange = firstOutOfRange(this.deliveryForm, DELIVERY_LIMITS);
    if (outOfRange) {
      return {
        id: DELIVERY_FIELD_IDS[outOfRange.field],
        message: rangeMessage(outOfRange, this.translate),
      };
    }
    const typedBirth = localInputInstant(this.deliveryForm.birthDateTime);
    const birth = typedBirth ?? new Date();
    const lastObservation = this.entries()
      .map((entry) => fromServerDateTime(entry.observationTime))
      .filter((when): when is Date => when !== null)
      .reduce<Date | null>((latest, when) => (!latest || when > latest ? when : latest), null);
    if (typedBirth && lastObservation && typedBirth < lastObservation) {
      return {
        id: DELIVERY_BIRTH_TIME_ID,
        message: this.translate.instant('LABOR.BIRTH_BEFORE_LAST_ENTRY'),
      };
    }
    const placenta = localInputInstant(this.deliveryForm.placentaDeliveredAt);
    if (placenta && placenta < birth) {
      return {
        id: DELIVERY_PLACENTA_TIME_ID,
        message: this.translate.instant('LABOR.PLACENTA_BEFORE_BIRTH'),
      };
    }
    return null;
  }

  /* ── Labour transfer dialogs ───────────────────────────── */

  openTransfer(): void {
    this.transferForm = {};
    this.transferOpen.set(true);
    if (this.destinations().length === 0) {
      this.hospitalService.directory().subscribe({
        next: (hospitals) => {
          const here = this.roleContext.effectiveHospitalIdForRequest();
          this.destinations.set((hospitals ?? []).filter((hospital) => hospital.id !== here));
        },
        error: () => this.destinations.set([]),
      });
    }
  }

  cancelTransfer(): void {
    this.transferOpen.set(false);
  }

  submitTransfer(): void {
    const patient = this.patient();
    const episode = this.activeEpisode();
    if (!patient || !episode || !this.canTransfer()) return;
    const reason = (this.transferForm.reason ?? '').trim();
    const missing = [
      this.transferForm.destinationHospitalId ? null : TRANSFER_FIELD_IDS['destinationHospitalId'],
      reason ? null : TRANSFER_FIELD_IDS['reason'],
    ].filter((id): id is string => id !== null);
    if (missing.length > 0) {
      const message = this.translate.instant('LABOR.TRANSFER.REQUIRED');
      refuseFields(this.host.nativeElement, missing, TRANSFER_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], TRANSFER_IDS, '');
    this.saving.set(true);
    this.laborService
      .transfer(patient.id, episode.id, {
        destinationHospitalId: this.transferForm.destinationHospitalId,
        reason,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('LABOR.TRANSFER.DONE'));
          this.saving.set(false);
          this.transferOpen.set(false);
          this.loadEpisodes(patient.id);
        },
        error: (err: unknown) => {
          this.markServerRefusals(err, TRANSFER_FIELD_IDS, TRANSFER_IDS);
          this.toast.error(
            labelledRefusalReason(err, this.translate, TRANSFER_LABELS) ??
              this.translate.instant('LABOR.TRANSFER.ERROR'),
          );
          this.saving.set(false);
        },
      });
  }

  openArrival(): void {
    this.arrivalReason = '';
    this.arrivalOpen.set(true);
  }

  cancelArrival(): void {
    this.arrivalOpen.set(false);
  }

  submitArrival(): void {
    const patient = this.patient();
    const episode = this.activeEpisode();
    if (!patient || !episode || !this.canRecordArrival()) return;
    const reason = this.arrivalReason.trim();
    if (!reason) {
      const message = this.translate.instant('LABOR.TRANSFER.REASON_REQUIRED');
      refuseFields(this.host.nativeElement, [ARRIVAL_FIELD_IDS['reason']], ARRIVAL_IDS, message);
      this.toast.error(message);
      return;
    }
    refuseFields(this.host.nativeElement, [], ARRIVAL_IDS, '');
    this.saving.set(true);
    this.laborService.arrivalWithoutHandover(patient.id, episode.id, { reason }).subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant('LABOR.ARRIVAL.DONE', { hospital: episode.hospitalName || '—' }),
        );
        this.saving.set(false);
        this.arrivalOpen.set(false);
        this.loadEpisodes(patient.id);
      },
      error: (err: unknown) => {
        this.markServerRefusals(err, ARRIVAL_FIELD_IDS, ARRIVAL_IDS);
        this.toast.error(
          labelledRefusalReason(err, this.translate, ARRIVAL_LABELS) ??
            this.translate.instant('LABOR.ARRIVAL.ERROR'),
        );
        this.saving.set(false);
      },
    });
  }

  /* ── Display helpers ───────────────────────────────────── */

  entryAlertClass(entry: PartographEntryResponse): string {
    if (entry.alerts?.some((alert) => alert.severity === 'URGENT')) return 'labor-row-urgent';
    if (entry.alerts?.some((alert) => alert.severity === 'CAUTION')) return 'labor-row-caution';
    return '';
  }
}
