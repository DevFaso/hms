import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
  computed,
  input,
  DestroyRef,
} from '@angular/core';
import { forkJoin, finalize } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { InBasketService, InBasketItem, InBasketSummary } from '../../services/in-basket.service';
import { ToastService } from '../../core/toast.service';
import { RovingFocusDirective } from '../../shared/a11y/roving-focus.directive';
import { currentLocale } from '../../shared/i18n/app-locale';

@Component({
  selector: 'app-in-basket-panel',
  standalone: true,
  imports: [CommonModule, TranslateModule, RovingFocusDirective],
  templateUrl: './in-basket-panel.html',
  styleUrl: './in-basket-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InBasketPanelComponent implements OnInit {
  readonly showTitle = input(true);
  private readonly destroyRef = inject(DestroyRef);
  private readonly inBasketService = inject(InBasketService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  /* ── state ── */
  items = signal<InBasketItem[]>([]);
  summary = signal<InBasketSummary>({
    totalUnread: 0,
    resultUnread: 0,
    orderUnread: 0,
    messageUnread: 0,
    taskUnread: 0,
  });
  loading = signal(false);
  loadError = signal(false);
  lastUpdated = signal<string | null>(null);
  activeFilter = signal<string | null>(null);

  /* ── derived ── */
  filteredItems = computed(() => {
    const filter = this.activeFilter();
    const all = this.items();
    if (!filter) return all;
    return all.filter((i) => i.itemType === filter);
  });

  badgeCount = computed(() => this.summary().totalUnread);

  ngOnInit(): void {
    this.loadData();
  }

  /* ── public API ── */

  setFilter(type: string | null): void {
    this.activeFilter.set(type);
  }

  markRead(item: InBasketItem): void {
    if (item.status !== 'UNREAD') return;
    this.inBasketService.markAsRead(item.id).subscribe({
      next: (updated) => {
        this.patchItem(updated);
        this.loadSummary();
      },
      error: () =>
        this.toast.error(
          this.translate.instant('inBasket.error.markRead') || 'Failed to mark as read',
        ),
    });
  }

  acknowledgeItem(item: InBasketItem): void {
    if (item.status === 'ACKNOWLEDGED') return;
    this.inBasketService.acknowledge(item.id).subscribe({
      next: (updated) => {
        this.patchItem(updated);
        this.loadSummary();
        this.toast.success(this.translate.instant('inBasket.acknowledged') || 'Item acknowledged');
      },
      error: () =>
        this.toast.error(
          this.translate.instant('inBasket.error.acknowledge') || 'Failed to acknowledge',
        ),
    });
  }

  refresh(): void {
    this.loadData();
  }

  /* ── helpers ── */

  priorityClass(priority: string): string {
    switch (priority) {
      case 'CRITICAL':
        return 'priority-critical';
      case 'URGENT':
        return 'priority-urgent';
      default:
        return 'priority-normal';
    }
  }

  typeIcon(type: string): string {
    const icons: Record<string, string> = {
      RESULT: 'science',
      ORDER: 'prescriptions',
      MESSAGE: 'mail',
      TASK: 'task_alt',
    };
    return icons[type] ?? 'inbox';
  }

  formatDate(iso: string | null): string {
    if (!iso) return '';
    const d = new Date(iso);
    // No hour12 override: the locale decides. Burkina Faso reads a 24-hour
    // clock; forcing 12-hour produced "15 juil., 10:00 AM" — French date,
    // American time.
    return d.toLocaleString(currentLocale(), {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  }

  trackById(_index: number, item: InBasketItem): string {
    return item.id;
  }

  private loadData(): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.loadError.set(false);
    forkJoin({
      page: this.inBasketService.getItems(),
      summary: this.inBasketService.getSummary(),
    })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: ({ page, summary }) => {
          this.items.set(page.content);
          this.summary.set(summary);
          this.lastUpdated.set(new Date().toISOString());
        },
        error: () => this.loadError.set(true),
      });
  }

  private loadSummary(): void {
    this.inBasketService
      .getSummary()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (s) => this.summary.set(s),
        error: () => this.toast.error(this.translate.instant('inBasket.summaryLoadFailed')),
      });
  }

  private patchItem(updated: InBasketItem): void {
    this.items.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
  }
}
