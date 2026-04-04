import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, PortalNotification } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-my-notifications',
  standalone: true,
  imports: [CommonModule, DatePipe, TranslateModule],
  templateUrl: './my-notifications.component.html',
  styleUrls: ['./my-notifications.component.scss', '../patient-portal-pages.scss'],
})
export class MyNotificationsComponent implements OnInit {
  private readonly portalService = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  notifications = signal<PortalNotification[]>([]);
  totalElements = signal(0);
  unreadCount = signal(0);
  loading = signal(true);
  markingAll = signal(false);
  activeTab = signal<'all' | 'unread'>('all');

  ngOnInit(): void {
    this.loadNotifications();
    this.loadUnreadCount();
  }

  loadNotifications(): void {
    this.loading.set(true);
    const readFilter = this.activeTab() === 'unread' ? false : undefined;
    this.portalService.getMyNotifications(readFilter, 0, 50).subscribe({
      next: (result) => {
        this.notifications.set(result.content);
        this.totalElements.set(result.totalElements);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('PORTAL.NOTIFICATIONS.LOAD_FAILED');
        this.loading.set(false);
      },
    });
  }

  loadUnreadCount(): void {
    this.portalService.getUnreadNotificationCount().subscribe({
      next: (count) => this.unreadCount.set(count),
    });
  }

  switchTab(tab: 'all' | 'unread'): void {
    this.activeTab.set(tab);
    this.loadNotifications();
  }

  markRead(notification: PortalNotification): void {
    if (notification.read) return;
    this.portalService.markNotificationRead(notification.id).subscribe({
      next: () => {
        this.notifications.update((list) =>
          list.map((n) => (n.id === notification.id ? { ...n, read: true } : n)),
        );
        this.unreadCount.update((c) => Math.max(c - 1, 0));
      },
      error: () => this.toast.error('PORTAL.NOTIFICATIONS.MARK_READ_FAILED'),
    });
  }

  markAllRead(): void {
    this.markingAll.set(true);
    this.portalService.markAllNotificationsRead().subscribe({
      next: (updated) => {
        this.notifications.update((list) => list.map((n) => ({ ...n, read: true })));
        this.unreadCount.set(0);
        this.markingAll.set(false);
        if (updated > 0) this.toast.success('PORTAL.NOTIFICATIONS.MARK_ALL_SUCCESS');
      },
      error: () => {
        this.markingAll.set(false);
        this.toast.error('PORTAL.NOTIFICATIONS.MARK_READ_FAILED');
      },
    });
  }
}
