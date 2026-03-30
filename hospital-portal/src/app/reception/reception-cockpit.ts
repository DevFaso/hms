import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  ReceptionService,
  ReceptionDashboardSummary,
  ReceptionQueueItem,
  InsuranceIssue,
  FlowBoard,
} from './reception.service';
import { ToastService } from '../core/toast.service';
import { PatientSnapshotDrawerComponent } from './patient-snapshot-drawer';
import { InsuranceIssuesPanelComponent } from './insurance-issues-panel';
import { PaymentPendingPanelComponent } from './payment-pending-panel';
import { FlowBoardComponent, FlowBoardStatusChange } from './flow-board';
import { WalkInDialogComponent } from './walkin-dialog';
import { WaitlistPanelComponent } from './waitlist-panel';

type Tab = 'queue' | 'insurance' | 'payments' | 'flowboard' | 'waitlist';
type StatusFilter =
  | 'ALL'
  | 'SCHEDULED'
  | 'CONFIRMED'
  | 'ARRIVED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'NO_SHOW'
  | 'WALK_IN';

function todayIso(): string {
  return new Date().toISOString().split('T')[0];
}

@Component({
  selector: 'app-reception-cockpit',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslateModule,
    PatientSnapshotDrawerComponent,
    InsuranceIssuesPanelComponent,
    PaymentPendingPanelComponent,
    FlowBoardComponent,
    WalkInDialogComponent,
    WaitlistPanelComponent,
  ],
  templateUrl: './reception-cockpit.html',
  styleUrl: './reception-cockpit.scss',
})
export class ReceptionCockpitComponent implements OnInit {
  private readonly receptionService = inject(ReceptionService);
  private readonly toast = inject(ToastService);

  readonly STATUS_FILTERS: StatusFilter[] = [
    'ALL',
    'SCHEDULED',
    'CONFIRMED',
    'ARRIVED',
    'IN_PROGRESS',
    'COMPLETED',
    'NO_SHOW',
    'WALK_IN',
  ];

  /* ── State ─────────────────────────────── */
  loading = signal(false);
  selectedDate = signal<string>(todayIso());
  activeTab = signal<Tab>('queue');
  statusFilter = signal<StatusFilter>('ALL');
  snapPatientId = signal<string | null>(null);
  showWalkIn = signal(false);

  summary = signal<ReceptionDashboardSummary | null>(null);
  queueItems = signal<ReceptionQueueItem[]>([]);
  insuranceIssues = signal<InsuranceIssue[]>([]);
  paymentsPending = signal<ReceptionQueueItem[]>([]);
  flowBoard = signal<FlowBoard | null>(null);

  filteredQueue = computed(() => {
    const f = this.statusFilter();
    const items = this.queueItems();
    return f === 'ALL' ? items : items.filter((i) => i.status === f);
  });

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    const date = this.selectedDate();
    this.loading.set(true);

    this.receptionService.getDashboardSummary(date).subscribe({
      next: (s) => this.summary.set(s),
      error: () => this.toast.error('Failed to load summary'),
    });

    this.receptionService.getQueue({ date }).subscribe({
      next: (items) => {
        this.queueItems.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load queue');
        this.loading.set(false);
      },
    });

    this.receptionService.getInsuranceIssues(date).subscribe({
      next: (issues) => this.insuranceIssues.set(issues),
      error: () => {
        /* silent — summary strip shows placeholder */
      },
    });

    this.receptionService.getPaymentsPending(date).subscribe({
      next: (items) => this.paymentsPending.set(items),
      error: () => {
        /* silent — panel shows empty state */
      },
    });

    this.receptionService.getFlowBoard(date).subscribe({
      next: (board) => this.flowBoard.set(board),
      error: () => {
        /* silent — flow board shows empty state */
      },
    });
  }

  onDateChange(): void {
    this.loadAll();
  }

  openSnapshot(patientId: string): void {
    this.snapPatientId.set(patientId);
  }

  closeSnapshot(): void {
    this.snapPatientId.set(null);
  }

  onWalkInCreated(): void {
    this.showWalkIn.set(false);
    this.loadAll();
    this.toast.success('Walk-in encounter created');
  }

  statusClass(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'badge-scheduled',
      CONFIRMED: 'badge-confirmed',
      ARRIVED: 'badge-arrived',
      IN_PROGRESS: 'badge-in-progress',
      NO_SHOW: 'badge-no-show',
      COMPLETED: 'badge-completed',
      WALK_IN: 'badge-walk-in',
      CANCELLED: 'badge-cancelled',
    };
    return 'status-badge ' + (map[status] ?? 'badge-default');
  }

  statusLabel(status: string): string {
    return status.replace(/_/g, ' ');
  }

  filterLabel(f: StatusFilter): string {
    return f === 'ALL' ? 'All' : f.replace(/_/g, ' ');
  }

  onFlowBoardStatusChanged(change: FlowBoardStatusChange): void {
    this.receptionService.updateEncounterStatus(change.encounterId, change.newStatus).subscribe({
      next: () => {
        this.toast.success('Status updated');
        this.receptionService.getFlowBoard(this.selectedDate()).subscribe({
          next: (board) => this.flowBoard.set(board),
        });
      },
      error: () => this.toast.error('Failed to update encounter status'),
    });
  }
}
