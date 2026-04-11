import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
  computed,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { InBasketService, InBasketItem, InBasketSummary } from '../../services/in-basket.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-in-basket-panel',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './in-basket-panel.html',
  styleUrl: './in-basket-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InBasketPanelComponent implements OnInit {
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
    switch (type) {
      case 'RESULT':
        return '🧪';
      case 'ORDER':
        return '📋';
      case 'MESSAGE':
        return '✉️';
      case 'TASK':
        return '✅';
      default:
        return '📌';
    }
  }

  formatDate(iso: string | null): string {
    if (!iso) return '';
    const d = new Date(iso);
    return d.toLocaleString('en-US', {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      hour12: true,
    });
  }

  trackById(_index: number, item: InBasketItem): string {
    return item.id;
  }

  private loadData(): void {
    this.loading.set(true);
    this.inBasketService.getItems().subscribe({
      next: (page) => {
        this.items.set(page.content);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
    this.loadSummary();
  }

  private loadSummary(): void {
    this.inBasketService.getSummary().subscribe({
      next: (s) => this.summary.set(s),
    });
  }

  private patchItem(updated: InBasketItem): void {
    this.items.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
  }
}
