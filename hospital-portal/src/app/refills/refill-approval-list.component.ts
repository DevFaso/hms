import { allRows } from '../shared/all-pages';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
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
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { refuseFields } from '../shared/forms/invalid-fields';
import { refusalReason, refusedFieldReasons } from '../shared/refusal-reason';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

const STATUS_FILTERS: { value: RefillStatus | 'ALL'; labelKey: string }[] = [
  { value: 'REQUESTED', labelKey: 'REFILLS.STATUS_REQUESTED' },
  { value: 'PAUSED', labelKey: 'REFILLS.STATUS_PAUSED' },
  { value: 'APPROVED', labelKey: 'REFILLS.STATUS_APPROVED' },
  { value: 'DENIED', labelKey: 'REFILLS.STATUS_DENIED' },
  { value: 'ALL', labelKey: 'REFILLS.STATUS_ALL' },
];

/** Statuses the prescriber can still act on — drives which cards show the action row. */
const OPEN_STATUSES: RefillStatus[] = ['REQUESTED', 'PAUSED'];

/** The id of a card's notes box (refused blank on a refusal or a hold). */
function notesFieldId(refillId: string): string {
  return `refill-notes-${refillId}`;
}

@Component({
  selector: 'app-refill-approval-list',
  standalone: true,
  imports: [ServerTimePipe, CommonModule, FormsModule, RouterLink, TranslateModule],
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
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
    // QA D1347: every refill request (all pages), not the 50 newest.
    allRows((page, size) => this.service.list(apiStatus, page, size)).subscribe({
      next: (list) => {
        this.refills.set(list);
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

  protected async reject(refill: RefillRequest): Promise<void> {
    const providerNotes = this.notesFor(refill);
    if (!providerNotes) {
      this.refuseNotes(refill, 'REFILLS.NOTES_REQUIRED_REJECT');
      return;
    }
    // QA CX-010: the note says why, it does not ask whether.
    const confirmed = await this.confirm.ask({
      titleKey: 'REFILLS.REJECT_CONFIRM',
      confirmKey: 'REFILLS.ACTION_REJECT',
    });
    if (!confirmed) return;
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
      this.refuseNotes(refill, 'REFILLS.NOTES_REQUIRED_PAUSE');
      return;
    }
    this.dispatch(
      refill,
      () => this.service.pause(refill.id, { providerNotes }),
      'REFILLS.TOAST_PAUSED',
      'REFILLS.TOAST_PAUSE_FAILED',
    );
  }

  /**
   * QA D1305 (D1306 class): a refusal or a hold without notes marks the
   * card's notes box itself (aria-invalid, the reason under it, focus), not
   * only a toast.
   */
  private refuseNotes(refill: RefillRequest, key: string): void {
    const message = this.translate.instant(key);
    const id = notesFieldId(refill.id);
    refuseFields(this.host.nativeElement, [id], [id], message);
    this.toast.error(message);
  }

  protected notesFieldId(id: string): string {
    return notesFieldId(id);
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
      error: (err: unknown) => {
        this.busyId.set(null);
        // QA D1315 (round 29): a deny or a hold the server refuses for its
        // notes (`fieldErrors.providerNotes`) marks the card's notes box with
        // the server's reason; any refusal shows the server's reason, never
        // only the generic failure (D1111 class).
        const id = notesFieldId(refill.id);
        const refused = refusedFieldReasons(err, { providerNotes: id });
        if (refused.length > 0) {
          refuseFields(this.host.nativeElement, [id], [id], refused[0][1]);
        }
        this.toast.error(refusalReason(err) ?? this.translate.instant(errorKey));
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
