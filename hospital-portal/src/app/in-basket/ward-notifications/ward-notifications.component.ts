import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { NurseInboxItem, NurseTaskService } from '../../services/nurse-task.service';
import { ToastService } from '../../core/toast.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refusalReason } from '../../shared/refusal-reason';

/**
 * QA D2582 (r65): the ward notifications (`GET /nurse/inbox`), on the in-basket page. A midwife's
 * maternity alerts and their reminders were in the nurse station's « Boîte de réception » tab while
 * the sidebar's « Boîte de réception » page, where the reminder sent her, was empty. Both pages now
 * show both lists: this one, and the acknowledgeable in-basket items.
 */
@Component({
  selector: 'app-ward-notifications',
  standalone: true,
  imports: [TranslateModule, DatePipe, ServerTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="ward-notifications"
      data-testid="ward-notifications"
      aria-labelledby="ward-notifications-title"
    >
      <h2 id="ward-notifications-title" class="ward-notifications__title">
        {{ 'WARD_INBOX.TITLE' | translate }}
      </h2>
      @if (failed()) {
        <p class="ward-notifications__state">{{ 'WARD_INBOX.FAILED' | translate }}</p>
      } @else if (items().length === 0) {
        <p class="ward-notifications__state">{{ 'WARD_INBOX.EMPTY' | translate }}</p>
      } @else {
        <ul class="ward-notifications__list">
          @for (item of items(); track item.id) {
            <li
              class="ward-notifications__item"
              [class.ward-notifications__item--unread]="!item.read"
              data-testid="ward-notification"
            >
              <p class="ward-notifications__message">{{ item.message }}</p>
              <div class="ward-notifications__meta">
                <span>{{ item.createdAt | serverTime | date: 'short' }}</span>
                @if (!item.read) {
                  <button type="button" class="btn-secondary" (click)="markRead(item)">
                    {{ 'WARD_INBOX.MARK_READ' | translate }}
                  </button>
                }
              </div>
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    .ward-notifications {
      margin-bottom: 20px;
      min-width: 0;
    }
    .ward-notifications__title {
      font-size: 1.05rem;
      font-weight: 600;
      margin: 0 0 8px;
    }
    .ward-notifications__state {
      margin: 0;
      color: #64748b;
      font-size: 0.875rem;
    }
    .ward-notifications__list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .ward-notifications__item {
      padding: 10px 12px;
      border: 1px solid #e2e8f0;
      border-radius: 6px;
      background: #fff;
      min-width: 0;
    }
    .ward-notifications__item--unread {
      border-left: 4px solid #dc2626;
    }
    .ward-notifications__message {
      margin: 0 0 4px;
      overflow-wrap: anywhere;
    }
    .ward-notifications__meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      font-size: 0.8rem;
      color: #64748b;
    }
  `,
})
export class WardNotificationsComponent implements OnInit {
  private readonly nurseService = inject(NurseTaskService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  readonly items = signal<NurseInboxItem[]>([]);
  readonly failed = signal(false);

  ngOnInit(): void {
    this.nurseService
      .getNurseInbox({ limit: 20 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => this.items.set(items ?? []),
        error: () => this.failed.set(true),
      });
  }

  markRead(item: NurseInboxItem): void {
    this.nurseService
      .markInboxRead(item.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () =>
          this.items.update((list) =>
            list.map((i) => (i.id === item.id ? { ...i, read: true } : i)),
          ),
        error: (err: unknown) =>
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('NURSE.TOAST.MARK_READ_FAILED'),
          ),
      });
  }
}
