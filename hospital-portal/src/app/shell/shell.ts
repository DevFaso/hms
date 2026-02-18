import { Component, computed, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';
import { NotificationService, Notification } from '../services/notification.service';

interface NavItem {
  icon: string;
  label: string;
  route: string;
  permission?: string;
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly router = inject(Router);
  protected readonly toast = inject(ToastService);
  private readonly notifService = inject(NotificationService);
  private notifSub?: Subscription;

  sidebarCollapsed = signal(false);
  profileMenuOpen = signal(false);
  notifPanelOpen = signal(false);
  unreadCount = signal(0);
  recentNotifications = signal<Notification[]>([]);

  userProfile = signal<LoginUserProfile | null>(null);

  userName = computed(() => {
    const u = this.auth.currentProfile() ?? this.userProfile();
    return u ? `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() : '';
  });
  userInitials = computed(() => {
    const u = this.auth.currentProfile() ?? this.userProfile();
    if (!u) return '?';
    return `${u.firstName?.charAt(0) ?? ''}${u.lastName?.charAt(0) ?? ''}`.toUpperCase();
  });
  userRole = computed(() => {
    const u = this.auth.currentProfile() ?? this.userProfile();
    if (!u || u.roles.length === 0) return '';
    return this.auth.formatRole(u.roles[0]);
  });
  userAvatarUrl = computed(() => {
    const u = this.auth.currentProfile() ?? this.userProfile();
    return u?.profileImageUrl ?? null;
  });

  navItems = computed<NavItem[]>(() => {
    const items: NavItem[] = [
      { icon: 'dashboard', label: 'Dashboard', route: '/dashboard' },
      { icon: 'people', label: 'Patients', route: '/patients', permission: 'View Patient Records' },
      {
        icon: 'calendar_month',
        label: 'Appointments',
        route: '/appointments',
        permission: 'View Appointments',
      },
      { icon: 'badge', label: 'Staff', route: '/staff', permission: 'View Staff' },
      { icon: 'event_note', label: 'Scheduling', route: '/scheduling', permission: 'View Staff' },
      {
        icon: 'domain',
        label: 'Departments',
        route: '/departments',
        permission: 'View Departments',
      },
      {
        icon: 'swap_horiz',
        label: 'Encounters',
        route: '/encounters',
        permission: 'View Patient Records',
      },
      {
        icon: 'hotel',
        label: 'Admissions',
        route: '/admissions',
        permission: 'View Patient Records',
      },
      {
        icon: 'medication',
        label: 'Prescriptions',
        route: '/prescriptions',
        permission: 'View Patient Records',
      },
      {
        icon: 'monitor_heart',
        label: 'Nurse Station',
        route: '/nurse-station',
        permission: 'View Patient Records',
      },
      {
        icon: 'radiology',
        label: 'Imaging',
        route: '/imaging',
        permission: 'View Patient Records',
      },
      {
        icon: 'forum',
        label: 'Consultations',
        route: '/consultations',
        permission: 'View Patient Records',
      },
      {
        icon: 'assignment',
        label: 'Treatment Plans',
        route: '/treatment-plans',
        permission: 'View Patient Records',
      },
      { icon: 'send', label: 'Referrals', route: '/referrals', permission: 'View Patient Records' },
      { icon: 'receipt_long', label: 'Billing', route: '/billing', permission: 'View Billing' },
      { icon: 'science', label: 'Laboratory', route: '/lab', permission: 'View Lab' },
      {
        icon: 'notifications',
        label: 'Notifications',
        route: '/notifications',
        permission: 'View Notifications',
      },
      { icon: 'chat', label: 'Messages', route: '/chat' },
    ];

    if (this.permissions.hasPermission('*')) {
      items.push(
        { icon: 'corporate_fare', label: 'Organizations', route: '/organizations' },
        { icon: 'manage_accounts', label: 'Users', route: '/users' },
        { icon: 'shield', label: 'Roles', route: '/roles' },
        { icon: 'hub', label: 'Platform', route: '/platform' },
        { icon: 'admin_panel_settings', label: 'Administration', route: '/admin' },
        { icon: 'policy', label: 'Audit Logs', route: '/audit-logs' },
      );
    }

    return items.filter(
      (item) => !item.permission || this.permissions.hasPermission(item.permission),
    );
  });

  ngOnInit(): void {
    this.userProfile.set(this.auth.getUserProfile());
    this.loadNotifications();
    const username = this.auth.getSubject();
    if (username) {
      this.notifService.connectWebSocket(username);
      this.notifSub = this.notifService.getNotificationStream().subscribe((n) => {
        this.recentNotifications.update((list) => [n, ...list].slice(0, 10));
        this.unreadCount.update((c) => c + 1);
      });
    }
  }

  ngOnDestroy(): void {
    this.notifSub?.unsubscribe();
    this.notifService.disconnectWebSocket();
  }

  private loadNotifications(): void {
    this.notifService.getNotifications({ page: 0, size: 10 }).subscribe({
      next: (page) => {
        this.recentNotifications.set(page.content);
        this.unreadCount.set(page.content.filter((n) => !n.read).length);
      },
    });
  }

  toggleSidebar(): void {
    this.sidebarCollapsed.update((v) => !v);
  }

  toggleProfileMenu(): void {
    this.profileMenuOpen.update((v) => !v);
    if (this.profileMenuOpen()) {
      this.notifPanelOpen.set(false);
    }
  }

  toggleNotifications(): void {
    this.notifPanelOpen.update((v) => !v);
    if (this.notifPanelOpen()) {
      this.profileMenuOpen.set(false);
    }
  }

  closeOverlays(): void {
    this.profileMenuOpen.set(false);
    this.notifPanelOpen.set(false);
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  dismissToast(id: number): void {
    this.toast.dismiss(id);
  }
}
