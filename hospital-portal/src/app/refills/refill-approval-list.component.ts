import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';

import {
  RefillApprovalService,
  RefillRequest,
  RefillStatus,
} from '../services/refill-approval.service';
import { ToastService } from '../core/toast.service';

const STATUS_FILTERS: { value: RefillStatus | 'ALL'; labelKey: string }[] = [
  { value: 'REQUESTED', labelKey: 'REFILLS.STATUS_REQUESTED' },
  { value: 'PAUSED', labelKey: 'REFILLS.STATUS_PAUSED' },
  { value: 'APPROVED', labelKey: 'REFILLS.STATUS_APPROVED' },
  { value: 'DENIED', labelKey: 'REFILLS.STATUS_DENIED' },
  { value: 'ALL', labelKey: 'REFILLS.STATUS_ALL' },
];

/** Statuses the prescriber can still act on — drives which cards show the action row. */
const OPEN_STATUSES: RefillStatus[] = ['REQUESTED', 'PAUSED'];

@Component({
  selector: 'app-refill-approval-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './refill-approval-list.component.html',
  styleUrls: ['./refill-approval-list.component.scss'],
})
export class RefillApprovalListComponent implements OnInit {
  protected readonly statusFilters = STATUS_FILTERS;
  protected readonly activeFilter = signal<RefillStatus | 'ALL'>('REQUESTED');
  protected readonly refills = signal<RefillRequest[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadError = signal(false);
  protected readonly busyId = signal<string | null>(null);
  protected decisionNotes: Record<string, string> = {};

  private readonly service = inject(RefillApprovalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  ngOnInit(): void {
    this.loadRefills();
  }

  protected setFilter(value: RefillStatus | 'ALL'): void {
    this.activeFilter.set(value);
    this.loadRefills();
  }

  private loadRefills(): void {
    this.loading.set(true);
    this.loadError.set(false);
    const status = this.activeFilter();
    const apiStatus: RefillStatus | undefined = status === 'ALL' ? undefined : status;
    this.service.list(apiStatus, 0, 50).subscribe({
      next: (page) => {
        this.refills.set(page.content ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  protected approve(refill: RefillRequest): void {
    const providerNotes = this.notesFor(refill);
    this.dispatch(
      refill,
      () => this.service.approve(refill.id, providerNotes ? { providerNotes } : {}),
      'REFILLS.TOAST_APPROVED',
      'REFILLS.TOAST_APPROVE_FAILED',
    );
  }

  protected reject(refill: RefillRequest): void {
    const providerNotes = this.notesFor(refill);
    if (!providerNotes) {
      this.toast.error(this.translate.instant('REFILLS.NOTES_REQUIRED_REJECT'));
      return;
    }
    this.dispatch(
      refill,
      () => this.service.reject(refill.id, { providerNotes }),
      'REFILLS.TOAST_DENIED',
      'REFILLS.TOAST_DENY_FAILED',
    );
  }

  protected pause(refill: RefillRequest): void {
    const providerNotes = this.notesFor(refill);
    if (!providerNotes) {
      this.toast.error(this.translate.instant('REFILLS.NOTES_REQUIRED_PAUSE'));
      return;
    }
    this.dispatch(
      refill,
      () => this.service.pause(refill.id, { providerNotes }),
      'REFILLS.TOAST_PAUSED',
      'REFILLS.TOAST_PAUSE_FAILED',
    );
  }

  /** True while the request is still open to a decision. */
  protected isOpen(status: RefillStatus): boolean {
    return OPEN_STATUSES.includes(status);
  }

  /** Pausing an already-paused request is a no-op the backend rejects, so hide the button. */
  protected canPause(status: RefillStatus): boolean {
    return status === 'REQUESTED';
  }

  private notesFor(refill: RefillRequest): string {
    return (this.decisionNotes[refill.id] ?? '').trim();
  }

  /**
   * Takes a factory rather than an observable so the in-flight guard runs
   * before the service is touched at all — passing the observable in would
   * mean the call had already been built by the time we decided to skip it.
   */
  private dispatch(
    refill: RefillRequest,
    call: () => Observable<RefillRequest>,
    successKey: string,
    errorKey: string,
  ): void {
    if (this.busyId()) return;
    this.busyId.set(refill.id);
    call().subscribe({
      next: () => {
        this.busyId.set(null);
        this.toast.success(this.translate.instant(successKey));
        this.loadRefills();
      },
      error: () => {
        this.busyId.set(null);
        this.toast.error(this.translate.instant(errorKey));
      },
    });
  }

  protected statusBadgeClass(status: RefillStatus): string {
    switch (status) {
      case 'APPROVED':
        return 'badge badge--ok';
      case 'DENIED':
        return 'badge badge--danger';
      case 'CANCELLED':
        return 'badge badge--muted';
      case 'DISPENSED':
      case 'PAUSED':
        return 'badge badge--info';
      default:
        return 'badge badge--warning';
    }
  }
}
