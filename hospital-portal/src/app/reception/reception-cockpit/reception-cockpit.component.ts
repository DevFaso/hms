import {
  Component,
  inject,
  signal,
  computed,
  OnInit,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  ReceptionService,
  ReceptionDashboardSummary,
  ReceptionQueueItem,
  InsuranceIssue,
  FlowBoard,
} from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { PatientSnapshotDrawerComponent } from '../patient-snapshot-drawer/patient-snapshot-drawer.component';
import { InsuranceIssuesPanelComponent } from '../insurance-issues-panel/insurance-issues-panel.component';
import { PaymentPendingPanelComponent } from '../payment-pending-panel/payment-pending-panel.component';
import {
  FlowBoardCloseUnseen,
  FlowBoardComponent,
  FlowBoardStatusChange,
} from '../flow-board/flow-board.component';
import { CloseUnseenDialogComponent } from '../close-unseen-dialog/close-unseen-dialog.component';
import { WalkInDialogComponent } from '../walkin-dialog/walkin-dialog.component';
import { WaitlistPanelComponent } from '../waitlist-panel/waitlist-panel.component';
import { RecallsPanelComponent } from '../recalls-panel/recalls-panel.component';
import { CheckinDialogComponent } from '../checkin-dialog/checkin-dialog.component';
import { localDateString } from '../../shared/date-utils';

type Tab = 'queue' | 'insurance' | 'payments' | 'flowboard' | 'waitlist' | 'recalls';
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
  return localDateString();
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
    RecallsPanelComponent,
    CheckinDialogComponent,
    CloseUnseenDialogComponent,
  ],
  templateUrl: './reception-cockpit.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './reception-cockpit.component.scss',
})
export class ReceptionCockpitComponent implements OnInit {
  private readonly receptionService = inject(ReceptionService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

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
  checkinQueueItem = signal<ReceptionQueueItem | null>(null);
  /** QA D788: the never-seen visit the desk is closing without a consultation. */
  closeUnseenTarget = signal<FlowBoardCloseUnseen | null>(null);

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
      error: () => this.toast.error(this.translate.instant('RECEPTION.LOAD_SUMMARY_FAILED')),
    });

    this.receptionService.getQueue({ date }).subscribe({
      next: (items) => {
        this.queueItems.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('RECEPTION.LOAD_QUEUE_FAILED'));
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
    this.toast.success(this.translate.instant('RECEPTION.WALKIN_CREATED'));
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

  filterLabel(f: StatusFilter): string {
    if (f === 'ALL') return this.translate.instant('COMMON.ALL');
    const key = 'RECEPTION.' + f;
    return this.translate.instant(key);
  }

  onFlowBoardStatusChanged(change: FlowBoardStatusChange): void {
    this.receptionService.updateEncounterStatus(change.encounterId, change.newStatus).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.STATUS_UPDATED'));
        this.reloadFlowBoard();
      },
      error: (err) => {
        // QA D788: the server says why (a visit nobody saw, the clinician's
        // steps), and the card goes back where it was.
        this.toast.error(
          err?.error?.message ?? this.translate.instant('RECEPTION.STATUS_UPDATE_FAILED'),
        );
        this.reloadFlowBoard();
      },
    });
  }

  onCloseUnseenRequested(request: FlowBoardCloseUnseen): void {
    this.closeUnseenTarget.set(request);
  }

  onCloseUnseenDismissed(): void {
    this.closeUnseenTarget.set(null);
  }

  onUnseenVisitClosed(): void {
    this.closeUnseenTarget.set(null);
    this.loadAll();
  }

  private reloadFlowBoard(): void {
    this.receptionService.getFlowBoard(this.selectedDate()).subscribe({
      next: (board) => this.flowBoard.set(board),
    });
  }

  openCheckIn(item: ReceptionQueueItem): void {
    this.checkinQueueItem.set(item);
  }

  onCheckedIn(): void {
    this.checkinQueueItem.set(null);
    this.loadAll();
  }

  canCheckIn(item: ReceptionQueueItem): boolean {
    return !!item.appointmentId && (item.status === 'SCHEDULED' || item.status === 'CONFIRMED');
  }
}
