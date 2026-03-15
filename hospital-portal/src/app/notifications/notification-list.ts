import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ToastService } from '../core/toast.service';
import { Notification, NotificationService } from '../services/notification.service';

@Component({
  selector: 'app-notification-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './notification-list.html',
  styleUrl: './notification-list.scss',
})
export class NotificationListComponent implements OnInit {
  private readonly notifService = inject(NotificationService);
  private readonly toast = inject(ToastService);

  notifications = signal<Notification[]>([]);
  filtered = signal<Notification[]>([]);
  loading = signal(true);
  activeFilter = signal<'all' | 'unread' | 'read'>('all');
  expandedId = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.notifService.getNotifications({ page: 0, size: 100 }).subscribe({
      next: (page) => {
        const list = page?.content ?? [];
        this.notifications.set(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load notifications');
        this.loading.set(false);
      },
    });
  }

  setFilter(filter: 'all' | 'unread' | 'read'): void {
    this.activeFilter.set(filter);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.notifications();
    const filter = this.activeFilter();
    if (filter === 'unread') list = list.filter((n) => !n.read);
    else if (filter === 'read') list = list.filter((n) => n.read);
    this.filtered.set(list);
  }

  onNotifClick(notif: Notification): void {
    // Toggle expanded panel
    this.expandedId.set(this.expandedId() === notif.id ? null : notif.id);

    // Mark as read (fires PUT + notifies shell to decrement badge)
    if (!notif.read) {
      notif.read = true;
      this.notifications.update((arr) => [...arr]);
      this.applyFilter();
      this.notifService.markAsReadAndNotify(notif.id);
    }
  }

  markAllRead(): void {
    this.notifService.markAllReadAndNotify().subscribe({
      next: () => {
        this.notifications.update((arr) => arr.map((n) => ({ ...n, read: true })));
        this.applyFilter();
        this.toast.success('All notifications marked as read');
      },
      error: () => this.toast.error('Failed to mark all as read'),
    });
  }

  unreadCount(): number {
    return this.notifications().filter((n) => !n.read).length;
  }

  getTypeIcon(type: string): string {
    switch (type) {
      case 'APPOINTMENT':
        return 'calendar_month';
      case 'LAB_RESULT':
        return 'science';
      case 'BILLING':
        return 'receipt_long';
      case 'ALERT':
        return 'warning';
      case 'SYSTEM':
        return 'settings';
      default:
        return 'notifications';
    }
  }

  getTypeClass(type: string): string {
    switch (type) {
      case 'APPOINTMENT':
        return 'notif-icon-wrap type-appointment';
      case 'LAB_RESULT':
        return 'notif-icon-wrap type-lab';
      case 'BILLING':
        return 'notif-icon-wrap type-billing';
      case 'ALERT':
        return 'notif-icon-wrap type-alert';
      case 'SYSTEM':
        return 'notif-icon-wrap type-system';
      default:
        return 'notif-icon-wrap type-default';
    }
  }
}
