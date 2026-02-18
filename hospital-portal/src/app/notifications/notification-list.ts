import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ToastService } from '../core/toast.service';

interface Notification {
  id: string;
  title: string;
  message: string;
  type: string;
  read: boolean;
  createdAt: string;
}

@Component({
  selector: 'app-notification-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './notification-list.html',
  styleUrl: './notification-list.scss',
})
export class NotificationListComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);

  notifications = signal<Notification[]>([]);
  filtered = signal<Notification[]>([]);
  loading = signal(true);
  activeFilter = signal<'all' | 'unread' | 'read'>('all');

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.http.get<{ content: Notification[] }>('/notifications').subscribe({
      next: (res) => {
        const list = res?.content ?? (Array.isArray(res) ? res : []);
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

  markRead(notif: Notification): void {
    if (notif.read) return;
    this.http.patch(`/notifications/${notif.id}/read`, {}).subscribe({
      next: () => {
        notif.read = true;
        this.notifications.update((arr) => [...arr]);
        this.applyFilter();
      },
    });
  }

  markAllRead(): void {
    this.http.patch('/notifications/read-all', {}).subscribe({
      next: () => {
        this.notifications.update((arr) => arr.map((n) => ({ ...n, read: true })));
        this.applyFilter();
        this.toast.success('All notifications marked as read');
      },
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
