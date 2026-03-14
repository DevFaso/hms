import { Component, inject, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of, Subscription, interval } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import {
  NurseTaskService,
  NurseVitalTask,
  NurseMedicationTask,
  NurseOrderTask,
  NurseHandoff,
  NurseAnnouncement,
  NurseDashboardSummary,
} from '../services/nurse-task.service';
import { ToastService } from '../core/toast.service';

type FilterMode = 'me' | 'unit' | 'all';

@Component({
  selector: 'app-nurse-station',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './nurse-station.html',
  styleUrl: './nurse-station.scss',
})
export class NurseStationComponent implements OnInit, OnDestroy {
  private readonly nurseService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private refreshSub?: Subscription;

  /* ── State signals ──────────────────────────────────────── */
  activeSection = signal<'vitals' | 'medications' | 'orders' | 'handoffs'>('vitals');
  vitals = signal<NurseVitalTask[]>([]);
  medications = signal<NurseMedicationTask[]>([]);
  orders = signal<NurseOrderTask[]>([]);
  handoffs = signal<NurseHandoff[]>([]);
  announcements = signal<NurseAnnouncement[]>([]);
  summary = signal<NurseDashboardSummary | null>(null);
  loading = signal(true);
  filterMode = signal<FilterMode>('me');
  lastRefreshed = signal<Date | null>(null);

  /* MAR action state */
  actionInProgress = signal<string | null>(null);
  reasonPrompt = signal<{ taskId: string; action: string } | null>(null);
  reasonText = signal('');

  private static readonly REFRESH_INTERVAL_MS = 60_000;

  /* ── Lifecycle ──────────────────────────────────────────── */

  ngOnInit(): void {
    this.loadAll();
    this.refreshSub = interval(NurseStationComponent.REFRESH_INTERVAL_MS)
      .pipe(
        switchMap(() => {
          this.loadAll();
          return of(null);
        }),
      )
      .subscribe();
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  /* ── Data loading ──────────────────────────────────────── */

  loadAll(): void {
    this.loading.set(true);
    // 'me' = current nurse's patients, 'all' / 'unit' = all patients (unit scoping arrives in MVP 2)
    const mode = this.filterMode();
    const assignee = mode === 'me' ? 'me' : 'all';
    const params = { assignee } as { assignee?: string };

    forkJoin({
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
    }).subscribe(
      (results: {
        vitals: NurseVitalTask[];
        medications: NurseMedicationTask[];
        orders: NurseOrderTask[];
        handoffs: NurseHandoff[];
        announcements: NurseAnnouncement[];
        summary: NurseDashboardSummary | null;
      }) => {
        this.vitals.set(results.vitals ?? []);
        this.medications.set(results.medications ?? []);
        this.orders.set(results.orders ?? []);
        this.handoffs.set(results.handoffs ?? []);
        this.announcements.set(Array.isArray(results.announcements) ? results.announcements : []);
        this.summary.set(results.summary);
        this.loading.set(false);
        this.lastRefreshed.set(new Date());
      },
    );
  }

  /* ── Filter ────────────────────────────────────────────── */

  setFilter(mode: FilterMode): void {
    if (this.filterMode() !== mode) {
      this.filterMode.set(mode);
      this.loadAll();
    }
  }

  /* ── Tabs ──────────────────────────────────────────────── */

  setSection(s: 'vitals' | 'medications' | 'orders' | 'handoffs'): void {
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

  /* ── Vitals: navigate to patient record ────────────────── */

  recordVitals(patientId: string): void {
    if (patientId) {
      this.router.navigate(['/patients', patientId]);
    }
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
}
