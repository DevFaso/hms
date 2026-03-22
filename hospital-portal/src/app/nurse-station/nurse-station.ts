import {
  Component,
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
import { forkJoin, Observable, of, Subscription, interval } from 'rxjs';
import { catchError, exhaustMap, tap, finalize } from 'rxjs/operators';
import {
  NurseTaskService,
  NurseVitalTask,
  NurseMedicationTask,
  NurseOrderTask,
  NurseHandoff,
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
  NursePatient,
  NursingNoteResponse,
  NurseHandoffCreateRequest,
  FlowsheetEntry,
  FlowsheetEntryCreateRequest,
  BcmaCompliance,
} from '../services/nurse-task.service';
import { ToastService } from '../core/toast.service';

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
  | 'inbox'
  | 'patients'
  | 'flowsheets'
  | 'bcma';

@Component({
  selector: 'app-nurse-station',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './nurse-station.html',
  styleUrl: './nurse-station.scss',
})
export class NurseStationComponent implements OnInit, OnDestroy, AfterViewChecked {
  private readonly nurseService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private refreshSub?: Subscription;

  @ViewChild('reasonDialog') reasonDialogRef?: ElementRef<HTMLElement>;
  private dialogFocused = false;

  /* ── State signals ──────────────────────────────────────── */
  activeSection = signal<SectionType>('vitals');
  vitals = signal<NurseVitalTask[]>([]);
  medications = signal<NurseMedicationTask[]>([]);
  orders = signal<NurseOrderTask[]>([]);
  handoffs = signal<NurseHandoff[]>([]);
  announcements = signal<NurseAnnouncement[]>([]);
  summary = signal<NurseDashboardSummary | null>(null);
  loading = signal(true);
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

  /* MVP 14 signals */
  patients = signal<NursePatient[]>([]);
  nursingNotes = signal<NursingNoteResponse[]>([]);
  selectedPatientForNotes = signal<{ patientId: string; patientName: string } | null>(null);

  /* MVP 15 signals (Flowsheets + BCMA) */
  flowsheetEntries = signal<FlowsheetEntry[]>([]);
  flowsheetPatientId = signal<string | null>(null);
  flowsheetTypeFilter = signal<string>('');
  flowsheetRecordOpen = signal(false);
  flowsheetForm = signal<Partial<FlowsheetEntryCreateRequest>>({});
  flowsheetRecording = signal(false);
  bcmaCompliance = signal<BcmaCompliance | null>(null);
  bcmaLoading = signal(false);

  /* Task create dialog */
  taskCreateOpen = signal(false);
  taskForm = signal<Partial<NurseTaskCreateRequest>>({});
  taskCreating = signal(false);

  /* Care note dialog */
  careNoteFor = signal<{ patientId: string; patientName: string } | null>(null);
  careNoteForm = signal<NurseCareNoteRequest>({ template: 'DAR' });
  careNoteCreating = signal(false);

  /* Handoff create dialog */
  handoffCreateOpen = signal(false);
  handoffForm = signal<Partial<NurseHandoffCreateRequest>>({});
  handoffCreating = signal(false);
  handoffChecklistInput = signal('');
  handoffChecklist = signal<string[]>([]);

  /* MAR action state */
  actionInProgress = signal<string | null>(null);
  reasonPrompt = signal<{ taskId: string; action: string } | null>(null);
  reasonText = signal('');

  private static readonly REFRESH_INTERVAL_MS = 60_000;

  @HostListener('document:keydown.escape')
  onEscapeKey(): void {
    if (this.reasonPrompt()) {
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
    this.loadAll$().subscribe();
    this.refreshSub = interval(NurseStationComponent.REFRESH_INTERVAL_MS)
      .pipe(exhaustMap(() => this.loadAll$()))
      .subscribe();
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  /* ── Data loading ──────────────────────────────────────── */

  loadAll$(): Observable<void> {
    this.loading.set(true);
    const mode = this.filterMode();
    const assignee = mode === 'me' ? 'me' : 'all';
    const params = { assignee } as { assignee?: string };

    return forkJoin({
      vitals: this.nurseService.getVitalsDue(params).pipe(
        catchError(() => {
          this.toast.error('Failed to load vitals');
          return of([] as NurseVitalTask[]);
        }),
      ),
      medications: this.nurseService.getMedicationMAR(params).pipe(
        catchError(() => {
          this.toast.error('Failed to load medications');
          return of([] as NurseMedicationTask[]);
        }),
      ),
      orders: this.nurseService.getOrders(params).pipe(
        catchError(() => {
          this.toast.error('Failed to load orders');
          return of([] as NurseOrderTask[]);
        }),
      ),
      handoffs: this.nurseService.getHandoffs(params).pipe(
        catchError(() => {
          this.toast.error('Failed to load handoffs');
          return of([] as NurseHandoff[]);
        }),
      ),
      announcements: this.nurseService
        .getAnnouncements()
        .pipe(catchError(() => of([] as NurseAnnouncement[]))),
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
      nursingTasks: this.nurseService
        .getNursingTasks()
        .pipe(catchError(() => of([] as NurseTaskItem[]))),
      inboxItems: this.nurseService
        .getNurseInbox({ limit: 20 })
        .pipe(catchError(() => of([] as NurseInboxItem[]))),
      patients: this.nurseService
        .getPatients(params)
        .pipe(catchError(() => of([] as NursePatient[]))),
      bcmaCompliance: this.nurseService
        .getBcmaCompliance()
        .pipe(catchError(() => of(null as BcmaCompliance | null))),
    }).pipe(
      tap(
        (results: {
          vitals: NurseVitalTask[];
          medications: NurseMedicationTask[];
          orders: NurseOrderTask[];
          handoffs: NurseHandoff[];
          announcements: NurseAnnouncement[];
          summary: NurseDashboardSummary | null;
          workboard: NurseWorkboardPatient[];
          flowBoard: NurseFlowBoard | null;
          pendingAdmissions: NurseAdmissionSummary[];
          nursingTasks: NurseTaskItem[];
          inboxItems: NurseInboxItem[];
          patients: NursePatient[];
          bcmaCompliance: BcmaCompliance | null;
        }) => {
          this.vitals.set(results.vitals ?? []);
          this.medications.set(results.medications ?? []);
          this.orders.set(results.orders ?? []);
          this.handoffs.set(results.handoffs ?? []);
          this.announcements.set(Array.isArray(results.announcements) ? results.announcements : []);
          this.summary.set(results.summary);
          this.workboard.set(results.workboard ?? []);
          this.flowBoard.set(results.flowBoard);
          this.pendingAdmissions.set(results.pendingAdmissions ?? []);
          this.nursingTasks.set(results.nursingTasks ?? []);
          this.inboxItems.set(results.inboxItems ?? []);
          this.inboxUnreadCount.set((results.inboxItems ?? []).filter((i) => !i.read).length);
          this.patients.set(results.patients ?? []);
          this.bcmaCompliance.set(results.bcmaCompliance);
          this.lastRefreshed.set(new Date());
        },
      ),
      finalize(() => this.loading.set(false)),
    ) as unknown as Observable<void>;
  }

  loadAll(): void {
    this.loadAll$().subscribe();
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
        this.toast.success('Medication administered');
        this.actionInProgress.set(null);
        this.loadAll();
      },
      error: () => {
        this.toast.error('Failed to record administration');
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
      this.toast.error('A reason is required for Hold/Refuse');
      return;
    }
    this.actionInProgress.set(prompt.taskId);
    this.nurseService
      .administerMedication(prompt.taskId, { status: prompt.action, note: reason })
      .subscribe({
        next: () => {
          this.toast.success(`Medication ${prompt.action.toLowerCase()}`);
          this.actionInProgress.set(null);
          this.reasonPrompt.set(null);
          this.loadAll();
        },
        error: () => {
          this.toast.error('Failed to record action');
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
      this.toast.error('Please enter at least one vital sign value.');
      return;
    }
    this.vitalsCapturing.set(true);
    this.nurseService.captureVitals(target.patientId, form).subscribe({
      next: () => {
        this.toast.success(`Vitals recorded for ${target.patientName}`);
        this.vitalsCapturing.set(false);
        this.closeVitalsDialog();
        this.loadAll();
      },
      error: () => {
        this.toast.error('Failed to save vitals');
        this.vitalsCapturing.set(false);
      },
    });
  }

  /* ── Acuity display ─────────────────────────────────────── */

  acuityLabel(level: string): string {
    const map: Record<string, string> = {
      LEVEL_1_MINIMAL: 'L1 Minimal',
      LEVEL_2_MODERATE: 'L2 Moderate',
      LEVEL_3_MAJOR: 'L3 Major',
      LEVEL_4_SEVERE: 'L4 Severe',
      LEVEL_5_CRITICAL: 'L5 Critical',
    };
    return map[level] ?? level;
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

  /* ── Handoff: complete ─────────────────────────────────── */

  completeHandoff(handoffId: string): void {
    this.actionInProgress.set(handoffId);
    this.nurseService.completeHandoff(handoffId).subscribe({
      next: () => {
        this.toast.success('Handoff completed');
        this.actionInProgress.set(null);
        this.loadAll();
      },
      error: () => {
        this.toast.error('Failed to complete handoff');
        this.actionInProgress.set(null);
      },
    });
  }
  /* ── Handoff create ─────────────────────────────────────── */

  openHandoffCreate(): void {
    this.handoffForm.set({});
    this.handoffChecklist.set([]);
    this.handoffChecklistInput.set('');
    this.handoffCreateOpen.set(true);
  }

  closeHandoffCreate(): void {
    this.handoffCreateOpen.set(false);
    this.handoffForm.set({});
    this.handoffChecklist.set([]);
  }

  updateHandoffField(field: keyof NurseHandoffCreateRequest, value: string): void {
    this.handoffForm.update((f) => ({ ...f, [field]: value }));
  }

  addHandoffChecklistItem(): void {
    const item = this.handoffChecklistInput().trim();
    if (item) {
      this.handoffChecklist.update((list) => [...list, item]);
      this.handoffChecklistInput.set('');
    }
  }

  removeHandoffChecklistItem(index: number): void {
    this.handoffChecklist.update((list) => list.filter((_, i) => i !== index));
  }

  submitHandoff(): void {
    const form = this.handoffForm();
    if (!form.patientId || !form.direction) {
      this.toast.error('Patient and direction are required.');
      return;
    }
    const request: NurseHandoffCreateRequest = {
      patientId: form.patientId,
      direction: form.direction,
      note: form.note,
      checklistItems: this.handoffChecklist().length > 0 ? this.handoffChecklist() : undefined,
    };
    this.handoffCreating.set(true);
    this.nurseService.createHandoff(request).subscribe({
      next: () => {
        this.toast.success('Handoff created');
        this.handoffCreating.set(false);
        this.closeHandoffCreate();
        this.loadAll();
      },
      error: () => {
        this.toast.error('Failed to create handoff');
        this.handoffCreating.set(false);
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
      this.toast.error('Patient and category are required.');
      return;
    }
    this.taskCreating.set(true);
    this.nurseService.createNursingTask(form as NurseTaskCreateRequest).subscribe({
      next: (task) => {
        this.toast.success(`Task created for ${task.patientName}`);
        this.taskCreating.set(false);
        this.closeTaskCreate();
        this.loadAll();
      },
      error: () => {
        this.toast.error('Failed to create task');
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
          this.toast.success('Task completed');
          this.actionInProgress.set(null);
          this.loadAll();
        },
        error: () => {
          this.toast.error('Failed to complete task');
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
    const map: Record<string, string> = {
      DRESSING_CHANGE: 'Dressing Change',
      IV_CHECK: 'IV Check',
      CATHETER_CARE: 'Catheter Care',
      PAIN_REASSESSMENT: 'Pain Reassessment',
      MOBILITY_ASSIST: 'Mobility Assist',
      INTAKE_OUTPUT: 'I&O Recording',
      WOUND_CARE: 'Wound Care',
      VITALS_CHECK: 'Vitals Check',
      MED_ADMIN: 'Med Admin',
      ORDER_FOLLOWUP: 'Order Follow-up',
      NEURO_CHECK: 'Neuro Check',
      BLOOD_GLUCOSE: 'Blood Glucose',
      OTHER: 'Other',
    };
    return map[category] ?? category;
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
      error: () => this.toast.error('Failed to mark as read'),
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
        this.toast.success(`Care note saved for ${note.patientName}`);
        this.careNoteCreating.set(false);
        this.closeCareNote();
      },
      error: () => {
        this.toast.error('Failed to save care note');
        this.careNoteCreating.set(false);
      },
    });
  }

  /* ── Nursing Notes (MVP 14) ────────────────────────────── */

  viewNursingNotes(patient: NursePatient): void {
    this.selectedPatientForNotes.set({
      patientId: patient.patientId ?? patient.id,
      patientName: patient.displayName,
    });
    this.nurseService.getNursingNotes({ patientId: patient.patientId ?? patient.id }).subscribe({
      next: (notes) => this.nursingNotes.set(notes),
      error: () => {
        this.toast.error('Failed to load nursing notes');
        this.nursingNotes.set([]);
      },
    });
  }

  closeNursingNotes(): void {
    this.selectedPatientForNotes.set(null);
    this.nursingNotes.set([]);
  }

  /* ── MVP 15: Flowsheets ────────────────────────────────── */

  flowsheetTypes = [
    { value: 'INTAKE', label: 'Intake' },
    { value: 'OUTPUT', label: 'Output' },
    { value: 'PAIN_ASSESSMENT', label: 'Pain Assessment' },
    { value: 'NEURO_CHECK', label: 'Neuro Check' },
    { value: 'WOUND_ASSESSMENT', label: 'Wound Assessment' },
    { value: 'BLOOD_GLUCOSE', label: 'Blood Glucose' },
    { value: 'FALL_RISK', label: 'Fall Risk' },
    { value: 'SKIN_ASSESSMENT', label: 'Skin Assessment' },
    { value: 'RESTRAINT_CHECK', label: 'Restraint Check' },
  ];

  loadFlowsheets(patientId: string): void {
    this.flowsheetPatientId.set(patientId);
    const typeFilter = this.flowsheetTypeFilter();
    this.nurseService
      .getFlowsheetEntries(patientId, typeFilter ? { type: typeFilter } : undefined)
      .subscribe({
        next: (entries) => this.flowsheetEntries.set(entries),
        error: () => {
          this.toast.error('Failed to load flowsheet entries');
          this.flowsheetEntries.set([]);
        },
      });
  }

  onFlowsheetTypeChange(type: string): void {
    this.flowsheetTypeFilter.set(type);
    const pid = this.flowsheetPatientId();
    if (pid) this.loadFlowsheets(pid);
  }

  openFlowsheetRecord(): void {
    this.flowsheetForm.set({});
    this.flowsheetRecordOpen.set(true);
  }

  closeFlowsheetRecord(): void {
    this.flowsheetRecordOpen.set(false);
    this.flowsheetForm.set({});
  }

  updateFlowsheetField(field: keyof FlowsheetEntryCreateRequest, value: string): void {
    if (field === 'numericValue') {
      this.flowsheetForm.update((f) => ({
        ...f,
        [field]: value === '' ? undefined : Number(value),
      }));
    } else {
      this.flowsheetForm.update((f) => ({ ...f, [field]: value || undefined }));
    }
  }

  submitFlowsheet(): void {
    const form = this.flowsheetForm();
    if (!form.patientId || !form.type) {
      this.toast.error('Patient and type are required.');
      return;
    }
    this.flowsheetRecording.set(true);
    this.nurseService.recordFlowsheetEntry(form as FlowsheetEntryCreateRequest).subscribe({
      next: () => {
        this.toast.success('Flowsheet entry recorded');
        this.flowsheetRecording.set(false);
        this.closeFlowsheetRecord();
        if (this.flowsheetPatientId()) this.loadFlowsheets(this.flowsheetPatientId()!);
      },
      error: () => {
        this.toast.error('Failed to record flowsheet entry');
        this.flowsheetRecording.set(false);
      },
    });
  }

  flowsheetTypeLabel(type: string): string {
    return this.flowsheetTypes.find((t) => t.value === type)?.label ?? type;
  }

  /* ── MVP 15: BCMA Compliance ───────────────────────────── */

  refreshBcma(): void {
    this.bcmaLoading.set(true);
    this.nurseService.getBcmaCompliance().subscribe({
      next: (data) => {
        this.bcmaCompliance.set(data);
        this.bcmaLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load BCMA compliance');
        this.bcmaLoading.set(false);
      },
    });
  }
}
