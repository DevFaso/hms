import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import {
  RefillApprovalService,
  RefillRequest,
  RefillStatus,
} from '../services/refill-approval.service';
import { ToastService } from '../core/toast.service';

const STATUS_FILTERS: { value: RefillStatus | 'ALL'; labelKey: string }[] = [
  { value: 'REQUESTED', labelKey: 'REFILLS.STATUS_PENDING' },
  { value: 'APPROVED', labelKey: 'REFILLS.STATUS_APPROVED' },
  { value: 'DENIED', labelKey: 'REFILLS.STATUS_DENIED' },
  { value: 'ALL', labelKey: 'REFILLS.STATUS_ALL' },
];

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
    if (this.busyId()) return;
    this.busyId.set(refill.id);
    const providerNotes = (this.decisionNotes[refill.id] ?? '').trim();
    this.service.approve(refill.id, providerNotes ? { providerNotes } : {}).subscribe({
      next: () => {
        this.busyId.set(null);
        this.toast.success('Refill approved');
        this.loadRefills();
      },
      error: () => {
        this.busyId.set(null);
        this.toast.error('Could not approve refill');
      },
    });
  }

  protected reject(refill: RefillRequest): void {
    if (this.busyId()) return;
    const providerNotes = (this.decisionNotes[refill.id] ?? '').trim();
    if (!providerNotes) {
      this.toast.error('Please add a note explaining the rejection');
      return;
    }
    this.busyId.set(refill.id);
    this.service.reject(refill.id, { providerNotes }).subscribe({
      next: () => {
        this.busyId.set(null);
        this.toast.success('Refill denied');
        this.loadRefills();
      },
      error: () => {
        this.busyId.set(null);
        this.toast.error('Could not deny refill');
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
        return 'badge badge--info';
      default:
        return 'badge badge--warning';
    }
  }
}
