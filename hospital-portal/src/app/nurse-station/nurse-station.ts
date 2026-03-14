import { Component, inject, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { forkJoin, of, Subscription, interval } from 'rxjs';
import { catchError } from 'rxjs/operators';
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
import { RoleContextService } from '../core/role-context.service';

@Component({
  selector: 'app-nurse-station',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './nurse-station.html',
  styleUrl: './nurse-station.scss',
})
export class NurseStationComponent implements OnInit, OnDestroy {
  private readonly nurseService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);
  private readonly roleContext = inject(RoleContextService);

  activeSection = signal<'vitals' | 'medications' | 'orders' | 'handoffs'>('vitals');
  vitals = signal<NurseVitalTask[]>([]);
  medications = signal<NurseMedicationTask[]>([]);
  orders = signal<NurseOrderTask[]>([]);
  handoffs = signal<NurseHandoff[]>([]);
  announcements = signal<NurseAnnouncement[]>([]);
  summary = signal<NurseDashboardSummary | null>(null);
  loading = signal(true);

  filterMode = signal<'me' | 'unit' | 'all'>('me');

  private refreshSub?: Subscription;
  private static readonly REFRESH_INTERVAL_MS = 60_000;

  ngOnInit(): void {
    this.loadAll();
    this.refreshSub = interval(NurseStationComponent.REFRESH_INTERVAL_MS).subscribe(() =>
      this.loadAll(),
    );
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  loadAll(): void {
    this.loading.set(true);
    const params = this.buildParams();

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
        .getAnnouncements(params)
        .pipe(catchError(() => of([] as NurseAnnouncement[]))),
      summary: this.nurseService
        .getDashboardSummary(params)
        .pipe(catchError(() => of(null as NurseDashboardSummary | null))),
    }).subscribe((results) => {
      this.vitals.set(results.vitals ?? []);
      this.medications.set(results.medications ?? []);
      this.orders.set(results.orders ?? []);
      this.handoffs.set(results.handoffs ?? []);
      this.announcements.set(Array.isArray(results.announcements) ? results.announcements : []);
      this.summary.set(results.summary);
      this.loading.set(false);
    });
  }

  setSection(s: 'vitals' | 'medications' | 'orders' | 'handoffs'): void {
    this.activeSection.set(s);
  }

  setFilter(mode: 'me' | 'unit' | 'all'): void {
    this.filterMode.set(mode);
    this.loadAll();
  }

  refresh(): void {
    this.loadAll();
  }

  // ── Summary card counts (use summary endpoint, fallback to array length) ──

  vitalsCount(): number {
    return this.summary()?.vitalsOverdueCount ?? this.vitals().filter((v) => v.overdue).length;
  }

  medsCount(): number {
    return this.summary()?.medsOverdueCount ?? this.medications().length;
  }

  ordersCount(): number {
    return this.summary()?.ordersPendingCount ?? this.orders().length;
  }

  overdueCount(): number {
    return this.vitals().filter((v) => v.overdue).length;
  }

  // ── MAR actions ──

  administerMedication(task: NurseMedicationTask): void {
    this.nurseService.administerMedication(task.id, { status: 'GIVEN' }).subscribe({
      next: () => {
        this.toast.success(`${task.medication} administered for ${task.patientName}`);
        this.loadAll();
      },
      error: () => this.toast.error('Failed to administer medication'),
    });
  }

  holdMedication(task: NurseMedicationTask): void {
    this.nurseService
      .administerMedication(task.id, { status: 'HELD', note: 'Held by nurse' })
      .subscribe({
        next: () => {
          this.toast.success(`${task.medication} held for ${task.patientName}`);
          this.loadAll();
        },
        error: () => this.toast.error('Failed to hold medication'),
      });
  }

  refuseMedication(task: NurseMedicationTask): void {
    this.nurseService
      .administerMedication(task.id, { status: 'REFUSED', note: 'Patient refused' })
      .subscribe({
        next: () => {
          this.toast.success(`${task.medication} refused for ${task.patientName}`);
          this.loadAll();
        },
        error: () => this.toast.error('Failed to record refusal'),
      });
  }

  // ── Handoff actions ──

  completeHandoff(handoff: NurseHandoff): void {
    this.nurseService.completeHandoff(handoff.id).subscribe({
      next: () => {
        this.toast.success(`Handoff completed for ${handoff.patientName}`);
        this.loadAll();
      },
      error: () => this.toast.error('Failed to complete handoff'),
    });
  }

  // ── Helper ──

  private buildParams(): { hospitalId?: string; assignee?: string } {
    const params: { hospitalId?: string; assignee?: string } = {};
    const hospitalId = this.roleContext.activeHospitalId;
    if (hospitalId) {
      params.hospitalId = hospitalId;
    }
    const mode = this.filterMode();
    if (mode === 'me') {
      params.assignee = 'me';
    } else if (mode === 'all') {
      params.assignee = 'all';
    }
    // 'unit' uses default (no assignee param) — backend returns all for the hospital
    return params;
  }
}
