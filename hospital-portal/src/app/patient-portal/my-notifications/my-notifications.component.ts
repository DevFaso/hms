import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { PatientPortalService, PortalNotification } from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';
import { Router } from '@angular/router';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { refusalReason } from '../../shared/refusal-reason';

@Component({
  selector: 'app-my-notifications',
  standalone: true,
  imports: [ServerTimePipe, DatePipe, TranslateModule, EnumLabelPipe],
  templateUrl: './my-notifications.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./my-notifications.component.scss', '../patient-portal-pages.scss'],
})
export class MyNotificationsComponent implements OnInit {
  private readonly portalService = inject(PatientPortalService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  /**
   * QA D983: where a notification leads. Clicking a chat notification only
   * marked it read; it now opens the conversation, and the others open the
   * page they are about. QA D1110: every type the server writes is either
   * here or declared without a page, with its reason, in
   * scripts/lib/notification-destinations.test.mjs.
   */
  private static readonly DESTINATIONS: Record<
    string,
    { path: string; query?: Record<string, string> }
  > = {
    CHAT_MESSAGE: { path: '/chat', query: { open: 'unread' } },
    // QA D1110: a released (or corrected) lab result opens the patient's results.
    LAB_RESULT: { path: '/my-lab-results' },
    APPOINTMENT_REMINDER: { path: '/my-appointments' },
    PRENATAL_REMINDER: { path: '/my-appointments' },
    MEDICATION_REFILL: { path: '/my-medications' },
    DISCHARGE_SUMMARY: { path: '/my-summaries' },
    BREAK_GLASS_OPT_OUT_OVERRIDE: { path: '/my-sharing' },
    PHARMACY_EVENT: { path: '/my-medications' },
  };

  destinationOf(n: PortalNotification): { path: string; query?: Record<string, string> } | null {
    return (n.type && MyNotificationsComponent.DESTINATIONS[n.type]) || null;
  }

  /** Marks it read and, when it is about something, opens it. */
  open(n: PortalNotification): void {
    this.markRead(n);
    const to = this.destinationOf(n);
    if (to) void this.router.navigate([to.path], { queryParams: to.query });
  }

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
      error: (err: unknown) => {
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL.NOTIFICATIONS.LOAD_FAILED'),
        );
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
      error: (err: unknown) =>
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL.NOTIFICATIONS.MARK_READ_FAILED'),
        ),
    });
  }

  markAllRead(): void {
    this.markingAll.set(true);
    this.portalService.markAllNotificationsRead().subscribe({
      next: (updated) => {
        this.notifications.update((list) => list.map((n) => ({ ...n, read: true })));
        this.unreadCount.set(0);
        this.markingAll.set(false);
        if (updated > 0)
          this.toast.success(this.translate.instant('PORTAL.NOTIFICATIONS.MARK_ALL_SUCCESS'));
      },
      error: (err: unknown) => {
        this.markingAll.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('PORTAL.NOTIFICATIONS.MARK_READ_FAILED'),
        );
      },
    });
  }
}
