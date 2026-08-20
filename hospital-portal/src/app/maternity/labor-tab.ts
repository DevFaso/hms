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
import { PartographChartComponent } from './partograph-chart.component';

/**
 * Labor & Delivery tab (P1 #6): start a labor episode, chart WHO partograph
 * timepoints against the alert/action lines, file the delivery record.
 */
@Component({
  selector: 'app-labor-tab',
  standalone: true,
  imports: [
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
    if (!patient) return;
    this.saving.set(true);
    this.laborService.startEpisode(patient.id, this.startForm).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('LABOR.EPISODE_STARTED'));
        this.saving.set(false);
        this.startFormOpen.set(false);
        this.loadEpisodes(patient.id);
      },
      error: () => {
        this.toast.error(this.translate.instant('LABOR.EPISODE_START_ERROR'));
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
    this.saving.set(true);
    this.laborService.addEntry(patient.id, episode.id, form).subscribe({
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
      error: () => {
        this.toast.error(this.translate.instant('LABOR.ENTRY_SAVE_ERROR'));
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
    this.saving.set(true);
    this.laborService
      .recordDelivery(patient.id, episode.id, this.deliveryForm as DeliveryRecordRequest)
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
        error: () => {
          this.toast.error(this.translate.instant('LABOR.DELIVERY_SAVE_ERROR'));
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
