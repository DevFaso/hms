import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import {
  Component,
  DestroyRef,
  WritableSignal,
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
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';

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
    ServerTimePipe,
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

  private readonly destroyRef = inject(DestroyRef);
  private readEpoch = 0;
  readonly searchTerm = signal('');
  readonly attentionFilter = signal<'all' | 'insurance' | 'balance'>('all');
  readonly summaryLoading = signal(false);
  readonly insuranceLoading = signal(false);
  readonly paymentsLoading = signal(false);
  readonly flowLoading = signal(false);
  readonly summaryFailed = signal(false);
  readonly queueFailed = signal(false);
  readonly insuranceFailed = signal(false);
  readonly paymentsFailed = signal(false);
  readonly flowFailed = signal(false);
  clearQueueFilters(): void {
    this.searchTerm.set('');
    this.attentionFilter.set('all');
    this.statusFilter.set('ALL');
  }
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
    const term = this.searchTerm().trim().toLocaleLowerCase();
    return this.queueItems().filter(
      (item) =>
        (this.statusFilter() === 'ALL' || item.status === this.statusFilter()) &&
        (this.attentionFilter() !== 'insurance' || item.hasInsuranceIssue) &&
        (this.attentionFilter() !== 'balance' || item.hasOutstandingBalance) &&
        (!term ||
          [
            item.patientName,
            item.mrn,
            item.providerName,
            item.departmentName,
            item.appointmentReason,
          ].some((v) => v?.toLocaleLowerCase().includes(term))),
    );
  });

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    const date = this.selectedDate();
    const request = ++this.readEpoch;
    this.summary.set(null);
    this.queueItems.set([]);
    this.insuranceIssues.set([]);
    this.paymentsPending.set([]);
    this.flowBoard.set(null);
    this.loadPart(
      this.receptionService.getDashboardSummary(date),
      this.summaryLoading,
      this.summaryFailed,
      (value) => this.summary.set(value),
      request,
      'RECEPTION.LOAD_SUMMARY_FAILED',
    );
    this.loadPart(
      this.receptionService.getQueue({ date }),
      this.loading,
      this.queueFailed,
      (value) => this.queueItems.set(value ?? []),
      request,
      'RECEPTION.LOAD_QUEUE_FAILED',
    );
    this.loadPart(
      this.receptionService.getInsuranceIssues(date),
      this.insuranceLoading,
      this.insuranceFailed,
      (value) => this.insuranceIssues.set(value ?? []),
      request,
    );
    this.loadPart(
      this.receptionService.getPaymentsPending(date),
      this.paymentsLoading,
      this.paymentsFailed,
      (value) => this.paymentsPending.set(value ?? []),
      request,
    );
    this.loadPart(
      this.receptionService.getFlowBoard(date),
      this.flowLoading,
      this.flowFailed,
      (value) => this.flowBoard.set(value),
      request,
    );
  }

  private loadPart<T>(
    source: Observable<T>,
    loading: WritableSignal<boolean>,
    failed: WritableSignal<boolean>,
    apply: (value: T) => void,
    request: number,
    errorKey?: string,
  ): void {
    loading.set(true);
    failed.set(false);
    source.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => {
        if (request !== this.readEpoch) return;
        apply(value);
        loading.set(false);
      },
      error: () => {
        if (request !== this.readEpoch) return;
        failed.set(true);
        loading.set(false);
        if (errorKey) this.toast.error(this.translate.instant(errorKey));
      },
    });
  }

  onDateChange(): void {
    this.clearQueueFilters();
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
      LEFT_WITHOUT_BEING_SEEN: 'badge-cancelled',
    };
    return 'status-badge ' + (map[status] ?? 'badge-default');
  }

  filterLabel(f: StatusFilter): string {
    if (f === 'ALL') return this.translate.instant('COMMON.ALL');
    const key = 'RECEPTION.' + f;
    return this.translate.instant(key);
  }

  formatWait(minutes: number): string {
    if (!Number.isFinite(minutes)) return '—';
    const value = Math.round(minutes);
    return this.translate.instant(value >= 60 ? 'TRACKER.DURATION_HM' : 'TRACKER.DURATION_M', {
      hours: Math.floor(value / 60),
      minutes: value >= 60 ? value % 60 : value,
    });
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
    const request = this.readEpoch;
    this.receptionService
      .getFlowBoard(this.selectedDate())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (board) => {
          if (request === this.readEpoch) this.flowBoard.set(board);
        },
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
