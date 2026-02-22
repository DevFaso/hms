import { Component, computed, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';
import { IdleService } from '../core/idle.service';
import { NotificationService, Notification } from '../services/notification.service';
import { LockScreenComponent } from '../lock-screen/lock-screen';
import { NavOrderService } from './nav-order.service';

interface NavItem {
  icon: string;
  label: string;
  route: string;
  permission?: string;
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive, LockScreenComponent],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly router = inject(Router);
  protected readonly toast = inject(ToastService);
  private readonly notifService = inject(NotificationService);
  protected readonly idle = inject(IdleService);
  private readonly navOrder = inject(NavOrderService);
  private notifSub?: Subscription;

  sidebarCollapsed = signal(false);
  profileMenuOpen = signal(false);
  notifPanelOpen = signal(false);
  unreadCount = signal(0);
  recentNotifications = signal<Notification[]>([]);

  userProfile = signal<LoginUserProfile | null>(null);

  // ── Drag-and-drop state ──────────────────────────────────────
  /** Index of the item being dragged */
  dragIndex = signal<number | null>(null);
  /** Index of the item currently being dragged over */
  dragOverIndex = signal<number | null>(null);

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

  /** Base permission-filtered list — source of truth before ordering */
  private readonly baseNavItems = computed<NavItem[]>(() => {
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
        permission: 'Create Encounters',
      },
      {
        icon: 'hotel',
        label: 'Admissions',
        route: '/admissions',
        permission: 'Admit Patients',
      },
      {
        icon: 'medication',
        label: 'Prescriptions',
        route: '/prescriptions',
        permission: 'Create Prescriptions',
      },
      {
        icon: 'monitor_heart',
        label: 'Nurse Station',
        route: '/nurse-station',
        permission: 'Document Nursing Notes',
      },
      {
        icon: 'radiology',
        label: 'Imaging',
        route: '/imaging',
        permission: 'Request Imaging Studies',
      },
      {
        icon: 'forum',
        label: 'Consultations',
        route: '/consultations',
        permission: 'Request Consultations',
      },
      {
        icon: 'assignment',
        label: 'Treatment Plans',
        route: '/treatment-plans',
        permission: 'Create Treatment Plans',
      },
      { icon: 'send', label: 'Referrals', route: '/referrals', permission: 'Create Referrals' },
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
        { icon: 'local_hospital', label: 'Hospitals', route: '/hospitals' },
        { icon: 'corporate_fare', label: 'Organizations', route: '/organizations' },
        { icon: 'campaign', label: 'Announcements', route: '/announcements' },
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

  /**
   * User-reordered nav items.
   * Loaded from localStorage on init, then mutated in-place on each drop.
   */
  navItems = signal<NavItem[]>([]);

  ngOnInit(): void {
    this.userProfile.set(this.auth.getUserProfile());

    // Apply any saved order on top of the permission-filtered base list
    const base = this.baseNavItems();
    const saved = this.navOrder.load();
    this.navItems.set(saved ? this.navOrder.applyOrder(base, saved) : base);

    this.loadNotifications();
    this.idle.start();

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
    this.idle.stop();
  }

  private loadNotifications(): void {
    this.notifService.getNotifications({ page: 0, size: 10 }).subscribe({
      next: (page) => {
        this.recentNotifications.set(page.content);
        this.unreadCount.set(page.content.filter((n) => !n.read).length);
      },
    });
  }

  // ── Drag-and-drop handlers ───────────────────────────────────

  onDragStart(event: DragEvent, index: number): void {
    this.dragIndex.set(index);
    // Required for Firefox
    event.dataTransfer?.setData('text/plain', String(index));
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onDragOver(event: DragEvent, index: number): void {
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    this.dragOverIndex.set(index);
  }

  onDragLeave(): void {
    this.dragOverIndex.set(null);
  }

  onDrop(event: DragEvent, dropIndex: number): void {
    event.preventDefault();
    const fromIndex = this.dragIndex();
    if (fromIndex === null || fromIndex === dropIndex) {
      this.resetDrag();
      return;
    }

    this.navItems.update((items) => {
      const reordered = [...items];
      const [moved] = reordered.splice(fromIndex, 1);
      reordered.splice(dropIndex, 0, moved);
      this.navOrder.save(reordered.map((i) => i.route));
      return reordered;
    });

    this.resetDrag();
  }

  onDragEnd(): void {
    this.resetDrag();
  }

  private resetDrag(): void {
    this.dragIndex.set(null);
    this.dragOverIndex.set(null);
  }

  // ── Other handlers ───────────────────────────────────────────

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

  /** Called when user clicks "Sign out instead" on the lock screen */
  onLockScreenSignOut(): void {
    this.tearDownAndRedirect();
  }

  /** Called when a different person wants to sign in from the lock screen */
  onSwitchUser(): void {
    this.tearDownAndRedirect();
  }

  /** Shared tear-down: stop idle, log out, redirect to login */
  private tearDownAndRedirect(): void {
    this.idle.stop();
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  dismissToast(id: number): void {
    this.toast.dismiss(id);
  }
}
