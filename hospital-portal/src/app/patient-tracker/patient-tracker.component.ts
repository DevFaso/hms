import {
  Component,
  DestroyRef,
  OnInit,
  OnDestroy,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, interval } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RoleContextService } from '../core/role-context.service';
import {
  PatientTrackerService,
  PatientTrackerBoard,
  PatientTrackerItem,
} from '../services/patient-tracker.service';
import { PatientTrackerWsService } from '../services/patient-tracker-ws.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../core/toast.service';
import { CheckoutDialogComponent } from '../checkout/checkout-dialog/checkout-dialog.component';
import {
  EncounterService,
  EncounterResponse,
  AfterVisitSummary,
} from '../services/encounter.service';
import { RovingFocusDirective } from '../shared/a11y/roving-focus.directive';
import { TriageFormComponent } from '../nurse-station/triage-form/triage-form.component';

export interface TrackerColumn {
  key: keyof Pick<
    PatientTrackerBoard,
    | 'arrived'
    | 'triage'
    | 'waitingForPhysician'
    | 'inProgress'
    | 'awaitingResults'
    | 'readyForDischarge'
  >;
  label: string;
  colorClass: string;
}

import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';

@Component({
  selector: 'app-patient-tracker',
  standalone: true,
  imports: [
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    CheckoutDialogComponent,
    RovingFocusDirective,
    TriageFormComponent,
    EnumLabelPipe,
  ],
  templateUrl: './patient-tracker.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './patient-tracker.component.scss',
})
export class PatientTrackerComponent implements OnInit, OnDestroy {
  private readonly trackerService = inject(PatientTrackerService);
  private readonly trackerWs = inject(PatientTrackerWsService);
  private readonly auth = inject(AuthService);
  /**
   * QA D788: a visit IN_PROGRESS is checked out by its clinician (it closes their
   * consultation); the desk waits until the examination is completed.
   */
  get isClinician(): boolean {
    return this.auth.hasAnyRole(['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE']);
  }
  private readonly encounterService = inject(EncounterService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  private refreshSub?: Subscription;
  private wsEventsSub?: Subscription;
  private wsStateSub?: Subscription;
  /** Poll interval when the WebSocket is offline — preserves the original behaviour on flaky 3G/4G. */
  private static readonly POLL_OFFLINE_MS = 30_000;
  /** Slow heartbeat poll while WebSocket is connected — guards against missed events. */
  private static readonly POLL_ONLINE_MS = 120_000;
  private wsConnected = false;

  private readonly roleContext = inject(RoleContextService);
  private readonly destroyRef = inject(DestroyRef);
  private boardRequest = 0;
  readonly loadFailed = signal(false);
  readonly searchTerm = signal('');
  readonly departmentFilter = signal('');
  readonly stageFilter = signal('');
  readonly visibleColumns = computed(() =>
    this.columns.filter((c) => !this.stageFilter() || c.key === this.stageFilter()),
  );
  readonly departments = computed(() =>
    [
      ...new Set(
        this.columns
          .flatMap((c) => this.getColumnItems(c.key))
          .map((i) => i.departmentName)
          .concat(this.departmentFilter())
          .filter((v): v is string => !!v),
      ),
    ].sort(),
  );
  readonly visibleCount = computed(() =>
    this.visibleColumns().reduce((sum, c) => sum + this.getVisibleColumnItems(c.key).length, 0),
  );
  getVisibleColumnItems(key: string): PatientTrackerItem[] {
    const term = this.searchTerm().trim().toLocaleLowerCase();
    return this.getColumnItems(key).filter(
      (item) =>
        (!this.departmentFilter() || item.departmentName === this.departmentFilter()) &&
        (!term ||
          [
            item.patientName,
            item.mrn,
            item.assignedProvider,
            item.roomAssignment,
            item.departmentName,
          ].some((v) => v?.toLocaleLowerCase().includes(term))),
    );
  }
  clearFilters(): void {
    this.searchTerm.set('');
    this.departmentFilter.set('');
    this.stageFilter.set('');
  }
  formatWait(minutes: number): string {
    const value = Math.round(minutes);
    return this.translate.instant(value >= 60 ? 'TRACKER.DURATION_HM' : 'TRACKER.DURATION_M', {
      hours: Math.floor(value / 60),
      minutes: value >= 60 ? value % 60 : value,
    });
  }
  board = signal<PatientTrackerBoard | null>(null);
  loading = signal(false);
  lastRefreshed = signal<Date | null>(null);

  readonly columns: TrackerColumn[] = [
    { key: 'arrived', label: 'TRACKER.COL_CHECKED_IN', colorClass: 'col-arrived' },
    { key: 'triage', label: 'TRACKER.COL_TRIAGE', colorClass: 'col-triage' },
    { key: 'waitingForPhysician', label: 'TRACKER.COL_WAITING', colorClass: 'col-waiting' },
    { key: 'inProgress', label: 'TRACKER.COL_IN_PROGRESS', colorClass: 'col-in-progress' },
    { key: 'awaitingResults', label: 'TRACKER.COL_AWAITING_RESULTS', colorClass: 'col-awaiting' },
    { key: 'readyForDischarge', label: 'TRACKER.COL_READY_DISCHARGE', colorClass: 'col-discharge' },
  ];

  totalPatients = computed(() => this.board()?.totalPatients ?? 0);
  averageWait = computed(() => this.board()?.averageWaitMinutes ?? 0);

  getColumnItems(key: string): PatientTrackerItem[] {
    const b = this.board();
    if (!b) return [];
    return (b as unknown as Record<string, PatientTrackerItem[]>)[key] ?? [];
  }

  ngOnInit(): void {
    this.loadBoard();

    const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
    if (hospitalId) {
      this.wsStateSub = this.trackerWs.getConnectionState().subscribe((connected) => {
        this.wsConnected = connected;
        this.startPoll();
      });
      this.wsEventsSub = this.trackerWs.getEvents().subscribe(() => this.loadBoard());
      this.trackerWs.connect(hospitalId);
    }

    this.startPoll();
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
    this.wsEventsSub?.unsubscribe();
    this.wsStateSub?.unsubscribe();
    this.trackerWs.disconnect();
  }

  private startPoll(): void {
    this.refreshSub?.unsubscribe();
    const ms = this.wsConnected
      ? PatientTrackerComponent.POLL_ONLINE_MS
      : PatientTrackerComponent.POLL_OFFLINE_MS;
    this.refreshSub = interval(ms).subscribe(() => {
      if (!this.loading()) this.loadBoard();
    });
  }

  loadBoard(): void {
    const hospitalId = this.roleContext.effectiveHospitalIdForRequest();
    if (!hospitalId) return;

    const request = ++this.boardRequest;
    this.loading.set(true);
    this.trackerService
      .getTrackerBoard(hospitalId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          if (request !== this.boardRequest) return;
          this.board.set(data);
          this.loadFailed.set(false);
          this.lastRefreshed.set(new Date());
          this.loading.set(false);
        },
        error: () => {
          if (request !== this.boardRequest) return;
          this.loadFailed.set(true);
          this.loading.set(false);
        },
      });
  }

  refresh(): void {
    this.loadBoard();
  }

  getWaitClass(minutes: number): string {
    if (minutes >= 30) return 'wait-critical';
    if (minutes >= 15) return 'wait-warning';
    return 'wait-ok';
  }

  getAcuityClass(acuity: string): string {
    if (!acuity) return 'acuity-routine';
    const upper = acuity.toUpperCase();
    if (upper.startsWith('ESI-1') || upper === 'EMERGENT') return 'acuity-emergent';
    if (upper.startsWith('ESI-2') || upper === 'URGENT') return 'acuity-urgent';
    if (upper.startsWith('ESI-3')) return 'acuity-moderate';
    return 'acuity-routine';
  }

  /* ── Check-Out dialog (MVP 6) ─────────── */
  checkoutEncounter = signal<EncounterResponse | null>(null);
  showCheckoutDialog = signal(false);

  openCheckout(item: PatientTrackerItem): void {
    this.encounterService.getById(item.encounterId).subscribe({
      next: (encounter) => {
        this.checkoutEncounter.set(encounter);
        this.showCheckoutDialog.set(true);
      },
    });
  }

  onCheckoutDismissed(): void {
    this.showCheckoutDialog.set(false);
    this.checkoutEncounter.set(null);
  }

  onCheckoutCompleted(_avs: AfterVisitSummary): void {
    this.showCheckoutDialog.set(false);
    this.checkoutEncounter.set(null);
    this.loadBoard();
  }

  /* ── Triage form (D408) — the nurse records vitals + ESI for an arrived outpatient ─── */
  triageEncounter = signal<EncounterResponse | null>(null);

  openTriage(item: PatientTrackerItem): void {
    this.encounterService.getById(item.encounterId).subscribe({
      next: (encounter) => this.triageEncounter.set(encounter),
      error: (err: { error?: { message?: string } } | null) =>
        this.toast.error(
          err?.error?.message || this.translate.instant('TRACKER.TRIAGE_INCOMPLETE'),
        ),
    });
  }

  onTriageDismissed(): void {
    this.triageEncounter.set(null);
  }

  onTriageCompleted(): void {
    this.triageEncounter.set(null);
    this.loadBoard();
  }

  /* ── Complete Triage — advance TRIAGE → WAITING ─── */
  triageInProgress = signal(false);

  completeTriage(item: PatientTrackerItem): void {
    this.triageInProgress.set(true);
    this.encounterService.completeTriage(item.encounterId).subscribe({
      next: () => {
        this.triageInProgress.set(false);
        this.loadBoard();
      },
      error: (err: { error?: { message?: string } } | null) => {
        this.triageInProgress.set(false);
        // QA D155: the server refuses a triage with no vitals or ESI; say so
        // rather than leaving the card where it was without a word.
        this.toast.error(
          err?.error?.message || this.translate.instant('TRACKER.TRIAGE_INCOMPLETE'),
        );
      },
    });
  }

  /* ── Complete Examination — IN_PROGRESS → AWAITING / READY ─── */
  examInProgress = signal(false);

  completeExamination(item: PatientTrackerItem): void {
    this.examInProgress.set(true);
    this.encounterService.completeExamination(item.encounterId).subscribe({
      next: () => {
        this.examInProgress.set(false);
        this.loadBoard();
      },
      error: () => {
        this.examInProgress.set(false);
      },
    });
  }

  /* ── Ready for Discharge — AWAITING_RESULTS → READY ─── */
  readyInProgress = signal(false);

  markReadyForDischarge(item: PatientTrackerItem): void {
    this.readyInProgress.set(true);
    this.encounterService.markReadyForDischarge(item.encounterId).subscribe({
      next: () => {
        this.readyInProgress.set(false);
        this.loadBoard();
      },
      error: () => {
        this.readyInProgress.set(false);
      },
    });
  }
}
