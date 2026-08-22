import {
  Component,
  computed,
  inject,
  OnInit,
  OnDestroy,
  signal,
  HostListener,
  ViewChild,
  ElementRef,
  AfterViewChecked,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { defer, forkJoin, Observable, of, Subscription, interval } from 'rxjs';
import { catchError, debounceTime, exhaustMap, tap, finalize, map } from 'rxjs/operators';
import {
  NurseTaskService,
  NurseVitalTask,
  NurseMedicationTask,
  NurseOrderTask,
  NurseHandoff,
  NurseHandoffCreateRequest,
  NurseAnnouncement,
  NurseDashboardSummary,
  NurseWorkboardPatient,
  NurseFlowBoard,
  NurseAdmissionSummary,
  NurseVitalCaptureRequest,
  NurseTaskItem,
  NurseTaskCreateRequest,
  NurseInboxItem,
  NurseCareNoteRequest,
  NurseCareNoteResponse,
} from '../services/nurse-task.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EncounterService, EncounterResponse } from '../services/encounter.service';
import { PatientTrackerWsService } from '../services/patient-tracker-ws.service';
import { AuthService } from '../auth/auth.service';
import { TriageFormComponent } from './triage-form/triage-form.component';
import { NursingIntakeFormComponent } from './nursing-intake-form/nursing-intake-form.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

type FilterMode = 'me' | 'unit' | 'all';
type SectionType =
  | 'vitals'
  | 'medications'
  | 'orders'
  | 'handoffs'
  | 'workboard'
  | 'flowboard'
  | 'admissions'
  | 'tasks'
  | 'inbox';

@Component({
  selector: 'app-nurse-station',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TranslateModule,
    TriageFormComponent,
    NursingIntakeFormComponent,
    EnumLabelPipe,
    PatientPickerComponent,
  ],
  templateUrl: './nurse-station.html',
  styleUrl: './nurse-station.scss',
})
export class NurseStationComponent implements OnInit, OnDestroy, AfterViewChecked {
  private readonly nurseService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly encounterService = inject(EncounterService);
  private readonly translate = inject(TranslateService);
  private readonly trackerWs = inject(PatientTrackerWsService);
  private readonly auth = inject(AuthService);
  private refreshSub?: Subscription;
  private slowRefreshSub?: Subscription;
  private wsEventsSub?: Subscription;

  @ViewChild('reasonDialog') reasonDialogRef?: ElementRef<HTMLElement>;
  private dialogFocused = false;

  /* ── State signals ──────────────────────────────────────── */
  activeSection = signal<SectionType>('vitals');
  vitals = signal<NurseVitalTask[]>([]);
  medications = signal<NurseMedicationTask[]>([]);
  orders = signal<NurseOrderTask[]>([]);
  handoffs = signal<NurseHandoff[]>([]);
  /**
   * Completed handoffs, fetched on demand. The ?status= endpoint parameter
   * shipped in PR #462 with zero callers — the record of who handed off what,
   * completed by whom, existed and was unreadable from any UI.
   */
  handoffView = signal<'PENDING' | 'COMPLETED'>('PENDING');
  completedHandoffs = signal<NurseHandoff[]>([]);
  completedHandoffsLoading = signal(false);
  announcements = signal<NurseAnnouncement[]>([]);
  summary = signal<NurseDashboardSummary | null>(null);
  private pendingLoads = signal(0);
  loading = computed(() => this.pendingLoads() > 0);
  filterMode = signal<FilterMode>('me');
  lastRefreshed = signal<Date | null>(null);

  /* MVP 12 signals */
  workboard = signal<NurseWorkboardPatient[]>([]);
  flowBoard = signal<NurseFlowBoard | null>(null);
  pendingAdmissions = signal<NurseAdmissionSummary[]>([]);

  /* Vitals capture dialog state */
  vitalsCaptureFor = signal<{ patientId: string; patientName: string } | null>(null);
  vitalsForm = signal<NurseVitalCaptureRequest>({});
  vitalsCapturing = signal(false);

  /* MVP 13 signals */
  nursingTasks = signal<NurseTaskItem[]>([]);
  inboxItems = signal<NurseInboxItem[]>([]);
  inboxUnreadCount = signal(0);

  /* Task create dialog */
  taskCreateOpen = signal(false);
  taskForm = signal<Partial<NurseTaskCreateRequest>>({});
  taskCreating = signal(false);

  /* Handoff create dialog (P0 #1 — real SBAR handoffs) */
  handoffCreateOpen = signal(false);
  handoffForm = signal<Partial<NurseHandoffCreateRequest>>({});
  handoffPatient = signal<PatientResponse | null>(null);
  handoffCreating = signal(false);

  /* Care note dialog */
  careNoteFor = signal<{ patientId: string; patientName: string } | null>(null);
  careNoteForm = signal<NurseCareNoteRequest>({ template: 'DAR' });
  careNoteCreating = signal(false);

  /* MAR action state */
  actionInProgress = signal<string | null>(null);
  reasonPrompt = signal<{ taskId: string; action: string } | null>(null);
  reasonText = signal('');

  /* Triage & Nursing-Intake dialog state (MVP 2 + MVP 3) */
  triageEncounter = signal<EncounterResponse | null>(null);
  intakeEncounter = signal<EncounterResponse | null>(null);
  encounterLookupInProgress = signal(false);

  private static readonly FAST_REFRESH_MS = 60_000;
  private static readonly SLOW_REFRESH_MS = 300_000;
  /** Collapse bursts of tracker events into a single flow-panel refetch. */
  private static readonly WS_REFRESH_DEBOUNCE_MS = 2_000;

  @HostListener('document:keydown.escape')
  onEscapeKey(): void {
    if (this.triageEncounter()) {
      this.triageEncounter.set(null);
    } else if (this.intakeEncounter()) {
      this.intakeEncounter.set(null);
    } else if (this.reasonPrompt()) {
      this.cancelHoldRefuse();
    } else if (this.vitalsCaptureFor()) {
      this.closeVitalsDialog();
    } else if (this.handoffCreateOpen()) {
      this.closeHandoffCreate();
    }
  }

  ngAfterViewChecked(): void {
    if (this.reasonPrompt() && this.reasonDialogRef && !this.dialogFocused) {
      this.reasonDialogRef.nativeElement.focus();
      this.dialogFocused = true;
    }
    if (!this.reasonPrompt()) {
      this.dialogFocused = false;
    }
  }

  /* ── Lifecycle ──────────────────────────────────────────── */

  ngOnInit(): void {
    this.loadFast$().subscribe();
    this.loadSlow$().subscribe();
    // Polling stays as the heartbeat: vitals-due and MAR lists are
    // time-driven (items become due as the clock advances), so no server
    // event can replace the fast tier — the STOMP stream below only adds
    // immediacy for encounter-flow changes (task 24).
    this.refreshSub = interval(NurseStationComponent.FAST_REFRESH_MS)
      .pipe(exhaustMap(() => this.loadFast$()))
      .subscribe();
    this.slowRefreshSub = interval(NurseStationComponent.SLOW_REFRESH_MS)
      .pipe(exhaustMap(() => this.loadSlow$()))
      .subscribe();

    // Task 24: live encounter-status transitions from
    // /topic/patient-tracker/{hospitalId} refresh the flow-facing panels
    // (flow board, workboard, pending admissions, summary) within seconds
    // instead of waiting up to 5 min for the slow tier. Debounced so a
    // burst of transitions (e.g. discharge auto-completing several
    // encounters) triggers one refetch.
    const hospitalId = this.auth.getHospitalId();
    if (hospitalId) {
      this.wsEventsSub = this.trackerWs
        .getEvents()
        .pipe(
          debounceTime(NurseStationComponent.WS_REFRESH_DEBOUNCE_MS),
          exhaustMap(() => this.loadFlow$()),
        )
        .subscribe();
      this.trackerWs.connect(hospitalId);
    }
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
    this.slowRefreshSub?.unsubscribe();
    if (this.wsEventsSub) {
      this.wsEventsSub.unsubscribe();
      this.trackerWs.disconnect();
    }
  }

  /* ── Data loading ──────────────────────────────────────── */

  /** Fast tier (60 s): time-critical data that nurses need up-to-the-minute. */
  loadFast$(): Observable<void> {
    const mode = this.filterMode();
    const assignee = mode === 'me' ? 'me' : 'all';
    const params = { assignee } as { assignee?: string };

    return this.trackLoading(
      forkJoin({
        vitals: this.nurseService.getVitalsDue(params).pipe(
          catchError(() => {
            this.toast.error(this.translate.instant('NURSE.TOAST.VITALS_LOAD_FAILED'));
            return of([] as NurseVitalTask[]);
          }),
        ),
        medications: this.nurseService.getMedicationMAR(params).pipe(
          catchError(() => {
            this.toast.error(this.translate.instant('NURSE.TOAST.MEDS_LOAD_FAILED'));
            return of([] as NurseMedicationTask[]);
          }),
        ),
        summary: this.nurseService
          .getDashboardSummary(params)
          .pipe(catchError(() => of(null as NurseDashboardSummary | null))),
        nursingTasks: this.nurseService
          .getNursingTasks()
          .pipe(catchError(() => of([] as NurseTaskItem[]))),
        inboxItems: this.nurseService
          .getNurseInbox({ limit: 20 })
          .pipe(catchError(() => of([] as NurseInboxItem[]))),
      }).pipe(
        tap((results) => {
          this.vitals.set(results.vitals ?? []);
          this.medications.set(results.medications ?? []);
          this.summary.set(results.summary);
          this.nursingTasks.set(results.nursingTasks ?? []);
          this.inboxItems.set(results.inboxItems ?? []);
          this.inboxUnreadCount.set((results.inboxItems ?? []).filter((i) => !i.read).length);
          this.lastRefreshed.set(new Date());
        }),
      ),
    ).pipe(map(() => void 0));
  }

  /** Slow tier (300 s): less time-critical data that can tolerate a longer poll period. */
  loadSlow$(): Observable<void> {
    const mode = this.filterMode();
    const assignee = mode === 'me' ? 'me' : 'all';
    const params = { assignee } as { assignee?: string };

    return this.trackLoading(
      forkJoin({
        orders: this.nurseService.getOrders(params).pipe(
          catchError(() => {
            this.toast.error(this.translate.instant('NURSE.TOAST.ORDERS_LOAD_FAILED'));
            return of([] as NurseOrderTask[]);
          }),
        ),
        handoffs: this.nurseService.getHandoffs(params).pipe(
          catchError(() => {
            this.toast.error(this.translate.instant('NURSE.TOAST.HANDOFFS_LOAD_FAILED'));
            return of([] as NurseHandoff[]);
          }),
        ),
        announcements: this.nurseService
          .getAnnouncements()
          .pipe(catchError(() => of([] as NurseAnnouncement[]))),
        workboard: this.nurseService
          .getWorkboard(params)
          .pipe(catchError(() => of([] as NurseWorkboardPatient[]))),
        flowBoard: this.nurseService
          .getPatientFlow()
          .pipe(catchError(() => of(null as NurseFlowBoard | null))),
        pendingAdmissions: this.nurseService
          .getPendingAdmissions()
          .pipe(catchError(() => of([] as NurseAdmissionSummary[]))),
      }).pipe(
        tap((results) => {
          this.orders.set(results.orders ?? []);
          this.handoffs.set(results.handoffs ?? []);
          this.announcements.set(Array.isArray(results.announcements) ? results.announcements : []);
          this.workboard.set(results.workboard ?? []);
          this.flowBoard.set(results.flowBoard);
          this.pendingAdmissions.set(results.pendingAdmissions ?? []);
        }),
      ),
    ).pipe(map(() => void 0));
  }

  /**
   * Flow tier (event-driven, task 24): the panels an encounter-status
   * transition can invalidate. Refetched when a tracker STOMP event
   * arrives; errors stay silent because this is a background refresh on
   * top of still-running polls.
   */
  loadFlow$(): Observable<void> {
    const mode = this.filterMode();
    const assignee = mode === 'me' ? 'me' : 'all';
    const params = { assignee } as { assignee?: string };

    return forkJoin({
      summary: this.nurseService
        .getDashboardSummary(params)
        .pipe(catchError(() => of(null as NurseDashboardSummary | null))),
      workboard: this.nurseService
        .getWorkboard(params)
        .pipe(catchError(() => of([] as NurseWorkboardPatient[]))),
      flowBoard: this.nurseService
        .getPatientFlow()
        .pipe(catchError(() => of(null as NurseFlowBoard | null))),
      pendingAdmissions: this.nurseService
        .getPendingAdmissions()
        .pipe(catchError(() => of([] as NurseAdmissionSummary[]))),
    }).pipe(
      tap((results) => {
        if (results.summary) this.summary.set(results.summary);
        this.workboard.set(results.workboard ?? []);
        if (results.flowBoard) this.flowBoard.set(results.flowBoard);
        this.pendingAdmissions.set(results.pendingAdmissions ?? []);
        this.lastRefreshed.set(new Date());
      }),
      map(() => void 0),
    );
  }

  private trackLoading<T>(source$: Observable<T>): Observable<T> {
    return defer(() => {
      this.pendingLoads.update((count) => count + 1);
      return source$.pipe(
        finalize(() => this.pendingLoads.update((count) => Math.max(0, count - 1))),
      );
    });
  }

  loadAll(): void {
    this.loadFast$().subscribe();
    this.loadSlow$().subscribe();
  }

  /* ── Filter ────────────────────────────────────────────── */

  setFilter(mode: FilterMode): void {
    if (this.filterMode() !== mode) {
      this.filterMode.set(mode);
      this.loadAll();
    }
  }

  /* ── Tabs ──────────────────────────────────────────────── */

  setSection(s: SectionType): void {
    this.activeSection.set(s);
  }

  /* ── Manual refresh ────────────────────────────────────── */

  refresh(): void {
    this.loadAll();
  }

  /* ── Computed counts ───────────────────────────────────── */

  overdueCount(): number {
    return this.vitals().filter((v: NurseVitalTask) => v.overdue).length;
  }

  medsDueCount(): number {
    return (
      this.summary()?.medicationsDue ??
      this.medications().filter((m: NurseMedicationTask) => m.status === 'DUE').length
    );
  }

  medsOverdueCount(): number {
    return (
      this.summary()?.medicationsOverdue ??
      this.medications().filter((m: NurseMedicationTask) => m.status === 'OVERDUE').length
    );
  }

  /* ── MAR Actions ───────────────────────────────────────── */

  administerMedication(taskId: string): void {
    this.actionInProgress.set(taskId);
    this.nurseService.administerMedication(taskId, { status: 'GIVEN' }).subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant('NURSE.TOAST.MED_ACTION_RECORDED', {
            action: this.translate.instant('NURSE.MED_ACTION.GIVEN'),
          }),
        );
        this.actionInProgress.set(null);
        this.loadAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('NURSE.TOAST.ACTION_FAILED'));
        this.actionInProgress.set(null);
      },
    });
  }

  promptHoldRefuse(taskId: string, action: 'HELD' | 'REFUSED'): void {
    this.reasonPrompt.set({ taskId, action });
    this.reasonText.set('');
  }

  confirmHoldRefuse(): void {
    const prompt = this.reasonPrompt();
    if (!prompt) return;
    const reason = this.reasonText().trim();
    if (!reason) {
      this.toast.error(this.translate.instant('NURSE.TOAST.REASON_REQUIRED'));
      return;
    }
    this.actionInProgress.set(prompt.taskId);
    this.nurseService
      .administerMedication(prompt.taskId, { status: prompt.action, note: reason })
      .subscribe({
        next: () => {
          // prompt.action is already 'HELD' | 'REFUSED' (see promptHoldRefuse
          // signature), so feed it directly into the NURSE.MED_ACTION.* key.
          // The prior `=== 'HOLD'` check was always false and silently coerced
          // every Hold toast into "refused" — caught by Copilot review on PR #256.
          this.toast.success(
            this.translate.instant('NURSE.TOAST.MED_ACTION_RECORDED', {
              action: this.translate.instant('NURSE.MED_ACTION.' + prompt.action),
            }),
          );
          this.actionInProgress.set(null);
          this.reasonPrompt.set(null);
          this.loadAll();
        },
        error: () => {
          this.toast.error(this.translate.instant('NURSE.TOAST.ACTION_FAILED'));
          this.actionInProgress.set(null);
        },
      });
  }

  cancelHoldRefuse(): void {
    this.reasonPrompt.set(null);
    this.reasonText.set('');
  }

  /* ── Vitals: open inline capture dialog ────────────────── */

  recordVitals(patientId: string, patientName?: string): void {
    if (!patientId) return;
    this.vitalsCaptureFor.set({ patientId, patientName: patientName ?? 'Patient' });
    this.vitalsForm.set({});
  }

  closeVitalsDialog(): void {
    this.vitalsCaptureFor.set(null);
    this.vitalsForm.set({});
  }

  updateVitalsField(field: keyof NurseVitalCaptureRequest, value: string): void {
    const parsed = value === '' ? undefined : Number(value);
    this.vitalsForm.update((f) => ({ ...f, [field]: parsed }));
  }

  updateVitalsNotes(value: string): void {
    this.vitalsForm.update((f) => ({ ...f, notes: value || undefined }));
  }

  submitVitals(): void {
    const target = this.vitalsCaptureFor();
    if (!target) return;
    const form = this.vitalsForm();
    const hasAtLeastOne = Object.values(form).some((v) => v !== undefined && v !== '');
    if (!hasAtLeastOne) {
      this.toast.error(this.translate.instant('NURSE.TOAST.VITALS_REQUIRED'));
      return;
    }
    this.vitalsCapturing.set(true);
    this.nurseService.captureVitals(target.patientId, form).subscribe({
      next: () => {
        this.toast.success(
          this.translate.instant('NURSE.TOAST.VITALS_RECORDED', { patient: target.patientName }),
        );
        this.vitalsCapturing.set(false);
        this.closeVitalsDialog();
        this.loadAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('NURSE.TOAST.VITALS_SAVE_FAILED'));
        this.vitalsCapturing.set(false);
      },
    });
  }

  /* ── Acuity display ─────────────────────────────────────── */

  acuityLabel(level: string): string {
    // Map the backend enum to an i18n key. translate.instant resolves to the
    // currently-active language so the FR/EN/ES toggle in the header
    // re-renders these on the next change-detection pass without a reload.
    // Falls through to the raw enum value for any unknown level so the UI
    // stays useful if a future backend adds a new tier before the i18n
    // catalogue catches up.
    const map: Record<string, string> = {
      LEVEL_1_MINIMAL: 'NURSE.ACUITY_L1_MINIMAL',
      LEVEL_2_MODERATE: 'NURSE.ACUITY_L2_MODERATE',
      LEVEL_3_MAJOR: 'NURSE.ACUITY_L3_MAJOR',
      LEVEL_4_SEVERE: 'NURSE.ACUITY_L4_SEVERE',
      LEVEL_5_CRITICAL: 'NURSE.ACUITY_L5_CRITICAL',
    };
    const key = map[level];
    return key ? this.translate.instant(key) : level;
  }

  acuityCssClass(level: string): string {
    const map: Record<string, string> = {
      LEVEL_1_MINIMAL: 'acuity-1',
      LEVEL_2_MODERATE: 'acuity-2',
      LEVEL_3_MAJOR: 'acuity-3',
      LEVEL_4_SEVERE: 'acuity-4',
      LEVEL_5_CRITICAL: 'acuity-5',
    };
    return map[level] ?? '';
  }

  /* ── Handoffs: create + complete ───────────────────────── */

  openHandoffCreate(): void {
    this.handoffForm.set({});
    this.handoffPatient.set(null);
    this.handoffCreateOpen.set(true);
  }

  closeHandoffCreate(): void {
    this.handoffCreateOpen.set(false);
    this.handoffForm.set({});
    this.handoffPatient.set(null);
  }

  updateHandoffField(field: keyof NurseHandoffCreateRequest, value: string): void {
    this.handoffForm.update((f) => ({ ...f, [field]: value }));
  }

  onHandoffPatientChange(patient: PatientResponse | null): void {
    this.handoffPatient.set(patient);
    this.handoffForm.update((f) => ({ ...f, patientId: patient?.id }));
  }

  hospitalId(): string | null {
    return this.auth.getHospitalId();
  }

  submitCreateHandoff(): void {
    const form = this.handoffForm();
    if (!form.patientId || !form.direction?.trim()) {
      this.toast.error(this.translate.instant('NURSE.TOAST.HANDOFF_REQUIRED'));
      return;
    }
    this.handoffCreating.set(true);
    this.nurseService.createHandoff(form as NurseHandoffCreateRequest).subscribe({
      next: (handoff) => {
        this.toast.success(
          this.translate.instant('NURSE.TOAST.HANDOFF_CREATED', { patient: handoff.patientName }),
        );
        this.handoffCreating.set(false);
        this.closeHandoffCreate();
        this.loadAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('NURSE.TOAST.HANDOFF_CREATE_FAILED'));
        this.handoffCreating.set(false);
      },
    });
  }

  setHandoffView(view: 'PENDING' | 'COMPLETED'): void {
    this.handoffView.set(view);
    if (view === 'COMPLETED') {
      // Always refetched: the completed list changes with every completion,
      // and it is outside the polling tiers.
      this.loadCompletedHandoffs();
    }
  }

  private loadCompletedHandoffs(): void {
    const assignee = this.filterMode() === 'me' ? 'me' : 'all';
    this.completedHandoffsLoading.set(true);
    this.nurseService.getHandoffs({ assignee, status: 'COMPLETED' }).subscribe({
      next: (rows) => {
        this.completedHandoffs.set(rows);
        this.completedHandoffsLoading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('NURSE.TOAST.HANDOFFS_LOAD_FAILED'));
        this.completedHandoffsLoading.set(false);
      },
    });
  }

  completeHandoff(handoffId: string): void {
    this.actionInProgress.set(handoffId);
    this.nurseService.completeHandoff(handoffId).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('NURSE.TOAST.HANDOFF_COMPLETED'));
        this.actionInProgress.set(null);
        this.loadAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('NURSE.TOAST.HANDOFF_FAILED'));
        this.actionInProgress.set(null);
      },
    });
  }
  /* ── MVP 13: Task Board ─────────────────────────────────────── */

  openTaskCreate(): void {
    this.taskForm.set({});
    this.taskCreateOpen.set(true);
  }

  closeTaskCreate(): void {
    this.taskCreateOpen.set(false);
    this.taskForm.set({});
  }

  updateTaskField(field: keyof NurseTaskCreateRequest, value: string): void {
    this.taskForm.update((f) => ({ ...f, [field]: value }));
  }

  submitCreateTask(): void {
    const form = this.taskForm();
    if (!form.patientId || !form.category) {
      this.toast.error(this.translate.instant('NURSE.TOAST.TASK_REQUIRED'));
      return;
    }
    this.taskCreating.set(true);
    this.nurseService.createNursingTask(form as NurseTaskCreateRequest).subscribe({
      next: (task) => {
        this.toast.success(
          this.translate.instant('NURSE.TOAST.TASK_CREATED', { patient: task.patientName }),
        );
        this.taskCreating.set(false);
        this.closeTaskCreate();
        this.loadAll();
      },
      error: () => {
        this.toast.error(this.translate.instant('NURSE.TOAST.TASK_CREATE_FAILED'));
        this.taskCreating.set(false);
      },
    });
  }

  completeTask(taskId: string, note?: string): void {
    this.actionInProgress.set(taskId);
    this.nurseService
      .completeNursingTask(taskId, note ? { completionNote: note } : undefined)
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('NURSE.TOAST.TASK_COMPLETED'));
          this.actionInProgress.set(null);
          this.loadAll();
        },
        error: () => {
          this.toast.error(this.translate.instant('NURSE.TOAST.TASK_COMPLETE_FAILED'));
          this.actionInProgress.set(null);
        },
      });
  }

  taskPriorityClass(priority: string): string {
    const map: Record<string, string> = {
      STAT: 'priority-stat',
      URGENT: 'priority-urgent',
      ROUTINE: 'priority-routine',
    };
    return map[priority] ?? '';
  }

  taskCategoryLabel(category: string): string {
    // The NURSE.<CATEGORY> keys already exist in en/fr/es.json (added with
    // MVP 13). The previous hardcoded English copy was a leftover from the
    // initial scaffolding and was producing mixed-language output on the FR
    // toggle.
    const map: Record<string, string> = {
      DRESSING_CHANGE: 'NURSE.DRESSING_CHANGE',
      IV_CHECK: 'NURSE.IV_CHECK',
      CATHETER_CARE: 'NURSE.CATHETER_CARE',
      PAIN_REASSESSMENT: 'NURSE.PAIN_REASSESSMENT',
      MOBILITY_ASSIST: 'NURSE.MOBILITY_ASSIST',
      INTAKE_OUTPUT: 'NURSE.INTAKE_OUTPUT',
      WOUND_CARE: 'NURSE.WOUND_CARE',
      OTHER: 'NURSE.OTHER',
    };
    const key = map[category];
    return key ? this.translate.instant(key) : category;
  }

  /* ── MVP 13: Inbox ─────────────────────────────────────────── */

  markRead(itemId: string): void {
    this.nurseService.markInboxRead(itemId).subscribe({
      next: () => {
        this.inboxItems.update((items) =>
          items.map((i) => (i.id === itemId ? { ...i, read: true } : i)),
        );
        this.inboxUnreadCount.update((n) => Math.max(0, n - 1));
      },
      error: () => this.toast.error(this.translate.instant('NURSE.TOAST.MARK_READ_FAILED')),
    });
  }

  markAllRead(): void {
    const unread = this.inboxItems().filter((i) => !i.read);
    unread.forEach((i) => this.markRead(i.id));
  }

  /* ── MVP 13: Care Notes ────────────────────────────────────── */

  openCareNote(patientId: string, patientName: string): void {
    this.careNoteFor.set({ patientId, patientName });
    this.careNoteForm.set({ template: 'DAR' });
  }

  closeCareNote(): void {
    this.careNoteFor.set(null);
    this.careNoteForm.set({ template: 'DAR' });
  }

  updateCareNoteField(field: keyof NurseCareNoteRequest, value: string): void {
    this.careNoteForm.update((f) => ({ ...f, [field]: value }));
  }

  submitCareNote(): void {
    const target = this.careNoteFor();
    if (!target) return;
    const form = this.careNoteForm();
    this.careNoteCreating.set(true);
    this.nurseService.createCareNote(target.patientId, form).subscribe({
      next: (note: NurseCareNoteResponse) => {
        this.toast.success(
          this.translate.instant('NURSE.TOAST.NOTE_SAVED', { patient: note.patientName }),
        );
        this.careNoteCreating.set(false);
        this.closeCareNote();
      },
      error: () => {
        this.toast.error(this.translate.instant('NURSE.TOAST.NOTE_FAILED'));
        this.careNoteCreating.set(false);
      },
    });
  }

  /* ── MVP 2 + 3: Triage & Nursing-Intake dialogs ────────────── */

  /**
   * Look up the most recent non-completed encounter for a patient and open the triage dialog.
   */
  openTriage(patientId: string): void {
    this.lookupActiveEncounter(patientId, (enc) => this.triageEncounter.set(enc));
  }

  onTriageCompleted(): void {
    this.triageEncounter.set(null);
    this.loadAll();
  }

  /**
   * Look up the most recent non-completed encounter for a patient and open the intake dialog.
   */
  openIntake(patientId: string): void {
    this.lookupActiveEncounter(patientId, (enc) => this.intakeEncounter.set(enc));
  }

  onIntakeCompleted(): void {
    this.intakeEncounter.set(null);
    this.loadAll();
  }

  /**
   * Shared helper: find an active encounter for a patient, then invoke a callback.
   */
  private lookupActiveEncounter(
    patientId: string,
    callback: (enc: EncounterResponse) => void,
  ): void {
    this.encounterLookupInProgress.set(true);
    this.encounterService.list({ patientId }).subscribe({
      next: (encounters) => {
        this.encounterLookupInProgress.set(false);
        const active = encounters.find((e) => e.status !== 'COMPLETED' && e.status !== 'CANCELLED');
        if (active) {
          callback(active);
        } else {
          this.toast.error(this.translate.instant('NURSE.TOAST.NO_ENCOUNTER'));
        }
      },
      error: () => {
        this.encounterLookupInProgress.set(false);
        this.toast.error(this.translate.instant('NURSE.TOAST.ENCOUNTER_LOOKUP_FAILED'));
      },
    });
  }
}
