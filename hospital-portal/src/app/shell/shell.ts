import { Component, computed, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { IdleService } from '../core/idle.service';
import { NotificationService, Notification } from '../services/notification.service';
import { LockScreenComponent } from '../lock-screen/lock-screen';
import { NavOrderService } from './nav-order.service';

interface NavItem {
  icon: string;
  label: string;
  translationKey?: string;
  route: string;
  permission?: string;
  roles?: string[];
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    LockScreenComponent,
    TranslateModule,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly roleContext = inject(RoleContextService);
  private readonly router = inject(Router);
  protected readonly toast = inject(ToastService);
  private readonly notifService = inject(NotificationService);
  protected readonly idle = inject(IdleService);
  private readonly navOrder = inject(NavOrderService);
  readonly translate = inject(TranslateService);
  private notifSub?: Subscription;
  private readCountSub?: Subscription;

  currentLang = signal(localStorage.getItem('lang') || 'fr');

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
    // Show the role the user chose at login (or the only role they hold)
    const active = this.roleContext.activeRole;
    if (active) return this.auth.formatRole(active);
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
    // When the user selected "Patient" at login, show the patient MyChart nav.
    // Previously this used hasAnyRole(['ROLE_PATIENT']) which meant a dual-role
    // user (e.g. Nurse + Patient) would always get patient nav only.
    const activeRole = this.roleContext.activeRole;
    if (activeRole === 'ROLE_PATIENT') {
      return [
        {
          icon: 'dashboard',
          label: 'Dashboard',
          translationKey: 'NAV.DASHBOARD',
          route: '/dashboard',
        },
        {
          icon: 'calendar_month',
          label: 'My Appointments',
          translationKey: 'NAV.MY_APPOINTMENTS',
          route: '/my-appointments',
        },
        {
          icon: 'medication',
          label: 'Medications',
          translationKey: 'NAV.MEDICATIONS',
          route: '/my-medications',
        },
        {
          icon: 'science',
          label: 'Test Results',
          translationKey: 'NAV.TEST_RESULTS',
          route: '/my-lab-results',
        },
        {
          icon: 'monitor_heart',
          label: 'Vitals',
          translationKey: 'NAV.VITALS',
          route: '/my-vitals',
        },
        {
          icon: 'receipt_long',
          label: 'Billing',
          translationKey: 'NAV.BILLING',
          route: '/my-billing',
        },
        {
          icon: 'history',
          label: 'Visit History',
          translationKey: 'NAV.VISIT_HISTORY',
          route: '/my-visits',
        },
        {
          icon: 'folder_shared',
          label: 'My Records',
          translationKey: 'NAV.MY_RECORDS',
          route: '/my-records',
        },
        {
          icon: 'medical_information',
          label: 'Medical History',
          translationKey: 'NAV.MEDICAL_HISTORY',
          route: '/my-medical-history',
        },
        {
          icon: 'share',
          label: 'Record Sharing',
          translationKey: 'NAV.RECORD_SHARING',
          route: '/my-sharing',
        },
        {
          icon: 'summarize',
          label: 'Visit Summaries',
          translationKey: 'NAV.VISIT_SUMMARIES',
          route: '/my-summaries',
        },
        { icon: 'chat', label: 'Messages', translationKey: 'NAV.MESSAGES', route: '/chat' },
        {
          icon: 'notifications',
          label: 'Notifications',
          translationKey: 'NAV.NOTIFICATIONS',
          route: '/notifications',
        },
      ];
    }

    const items: NavItem[] = [
      {
        icon: 'dashboard',
        label: 'Dashboard',
        translationKey: 'NAV.DASHBOARD',
        route: '/dashboard',
      },
      {
        icon: 'people',
        label: 'Patients',
        translationKey: 'NAV.PATIENTS',
        route: '/patients',
        permission: 'View Patient Records',
      },
      {
        icon: 'calendar_month',
        label: 'Appointments',
        translationKey: 'NAV.APPOINTMENTS',
        route: '/appointments',
        permission: 'View Appointments',
      },
      {
        icon: 'badge',
        label: 'Staff',
        translationKey: 'NAV.STAFF',
        route: '/staff',
        permission: 'View Staff',
      },
      {
        icon: 'event_note',
        label: this.permissions.hasAnyPermission('Manage Staff', 'Manage Staff Schedules')
          ? 'Scheduling'
          : 'Availability',
        translationKey: this.permissions.hasAnyPermission('Manage Staff', 'Manage Staff Schedules')
          ? 'NAV.SCHEDULING'
          : 'NAV.AVAILABILITY',
        route: '/scheduling',
        permission: 'View Staff Schedules',
        roles: [
          'ROLE_DOCTOR',
          'ROLE_NURSE',
          'ROLE_MIDWIFE',
          'ROLE_RECEPTIONIST',
          'ROLE_HOSPITAL_ADMIN',
          'ROLE_ADMIN',
          'ROLE_SUPER_ADMIN',
          'ROLE_LAB_DIRECTOR',
          'ROLE_LAB_MANAGER',
          'ROLE_LAB_SCIENTIST',
          'ROLE_QUALITY_MANAGER',
        ],
      },
      {
        icon: 'domain',
        label: 'Departments',
        translationKey: 'NAV.DEPARTMENTS',
        route: '/departments',
        permission: 'View Departments',
        roles: [
          'ROLE_HOSPITAL_ADMIN',
          'ROLE_ADMIN',
          'ROLE_SUPER_ADMIN',
          'ROLE_RECEPTIONIST',
          'ROLE_LAB_DIRECTOR',
          'ROLE_LAB_MANAGER',
          'ROLE_LAB_SCIENTIST',
          'ROLE_QUALITY_MANAGER',
        ],
      },
      {
        icon: 'swap_horiz',
        label: 'Encounters',
        translationKey: 'NAV.ENCOUNTERS',
        route: '/encounters',
        permission: 'Create Encounters',
      },
      {
        icon: 'hotel',
        label: 'Admissions',
        translationKey: 'NAV.ADMISSIONS',
        route: '/admissions',
        permission: 'Admit Patients',
      },
      {
        icon: 'medication',
        label: 'Prescriptions',
        translationKey: 'NAV.PRESCRIPTIONS',
        route: '/prescriptions',
        permission: 'View Prescriptions',
      },
      {
        icon: 'monitor_heart',
        label: 'Nurse Station',
        translationKey: 'NAV.NURSE_STATION',
        route: '/nurse-station',
        permission: 'Access Nurse Station',
      },
      {
        icon: 'view_kanban',
        label: 'Patient Tracker',
        translationKey: 'NAV.PATIENT_TRACKER',
        route: '/patient-tracker',
        permission: 'View Patient Records',
        roles: [
          'ROLE_DOCTOR',
          'ROLE_NURSE',
          'ROLE_MIDWIFE',
          'ROLE_RECEPTIONIST',
          'ROLE_HOSPITAL_ADMIN',
          'ROLE_ADMIN',
          'ROLE_SUPER_ADMIN',
        ],
      },
      {
        icon: 'inbox',
        label: 'In-Basket',
        translationKey: 'NAV.IN_BASKET',
        route: '/in-basket',
        permission: 'View Patient Records',
        roles: [
          'ROLE_DOCTOR',
          'ROLE_NURSE',
          'ROLE_MIDWIFE',
          'ROLE_LAB_TECHNICIAN',
          'ROLE_LAB_MANAGER',
          'ROLE_HOSPITAL_ADMIN',
          'ROLE_SUPER_ADMIN',
        ],
      },
      {
        icon: 'radiology',
        label: 'Imaging',
        translationKey: 'NAV.IMAGING',
        route: '/imaging',
        permission: 'View Imaging Studies',
      },
      {
        icon: 'forum',
        label: 'Consultations',
        translationKey: 'NAV.CONSULTATIONS',
        route: '/consultations',
        permission: 'Request Consultations',
      },
      {
        icon: 'assignment',
        label: 'Treatment Plans',
        translationKey: 'NAV.TREATMENT_PLANS',
        route: '/treatment-plans',
        permission: 'Create Treatment Plans',
      },
      {
        icon: 'send',
        label: 'Referrals',
        translationKey: 'NAV.REFERRALS',
        route: '/referrals',
        permission: 'Create Referrals',
      },
      {
        icon: 'receipt_long',
        label: 'Billing',
        translationKey: 'NAV.BILLING',
        route: '/billing',
        permission: 'View Billing',
      },
      {
        icon: 'science',
        label: 'Laboratory',
        translationKey: 'NAV.LABORATORY',
        route: '/lab',
        permission: 'View Lab',
      },
      {
        icon: 'biotech',
        label: 'Lab Results',
        translationKey: 'NAV.LAB_RESULTS',
        route: '/lab-results',
        permission: 'View Lab',
      },
      {
        icon: 'notifications',
        label: 'Notifications',
        translationKey: 'NAV.NOTIFICATIONS',
        route: '/notifications',
        permission: 'View Notifications',
      },
      { icon: 'chat', label: 'Messages', translationKey: 'NAV.MESSAGES', route: '/chat' },
      {
        icon: 'campaign',
        label: 'Announcements',
        translationKey: 'NAV.ANNOUNCEMENTS',
        route: '/announcements',
      },
    ];

    // Admin items — gated individually so HOSPITAL_ADMIN sees relevant ones
    if (this.permissions.hasPermission('View Hospitals')) {
      items.push({
        icon: 'local_hospital',
        label: 'Hospitals',
        translationKey: 'NAV.HOSPITALS',
        route: '/hospitals',
      });
    }
    if (this.permissions.hasPermission('*')) {
      items.push(
        {
          icon: 'corporate_fare',
          label: 'Organizations',
          translationKey: 'NAV.ORGANIZATIONS',
          route: '/organizations',
        },
        { icon: 'manage_accounts', label: 'Users', translationKey: 'NAV.USERS', route: '/users' },
        { icon: 'shield', label: 'Roles', translationKey: 'NAV.ROLES', route: '/roles' },
        { icon: 'hub', label: 'Platform', translationKey: 'NAV.PLATFORM', route: '/platform' },
        {
          icon: 'admin_panel_settings',
          label: 'Administration',
          translationKey: 'NAV.ADMINISTRATION',
          route: '/admin',
        },
      );
    }
    if (this.permissions.hasPermission('View Audit Logs')) {
      items.push({
        icon: 'policy',
        label: 'Audit Logs',
        translationKey: 'NAV.AUDIT_LOGS',
        route: '/audit-logs',
      });
    }
    // Pharmacy Module
    if (
      this.hasAnyRole([
        'ROLE_PHARMACIST',
        'ROLE_INVENTORY_CLERK',
        'ROLE_STORE_MANAGER',
        'ROLE_HOSPITAL_ADMIN',
        'ROLE_SUPER_ADMIN',
      ])
    ) {
      items.push(
        {
          icon: 'medication',
          label: 'Medication Catalog',
          translationKey: 'NAV.MEDICATION_CATALOG',
          route: '/medication-catalog',
        },
        {
          icon: 'local_pharmacy',
          label: 'Pharmacy Registry',
          translationKey: 'NAV.PHARMACY_REGISTRY',
          route: '/pharmacy-registry',
        },
        {
          icon: 'inventory_2',
          label: 'Inventory',
          translationKey: 'NAV.PHARMACY_INVENTORY',
          route: '/pharmacy/inventory',
        },
        {
          icon: 'add_box',
          label: 'Goods Receipt',
          translationKey: 'NAV.GOODS_RECEIPT',
          route: '/pharmacy/goods-receipt',
        },
        {
          icon: 'swap_vert',
          label: 'Stock Adjustment',
          translationKey: 'NAV.STOCK_ADJUSTMENT',
          route: '/pharmacy/stock-adjustment',
        },
        {
          icon: 'medication',
          label: 'Dispensing',
          translationKey: 'NAV.DISPENSING',
          route: '/pharmacy/dispensing',
        },
        {
          icon: 'alt_route',
          label: 'Stock Routing',
          translationKey: 'NAV.STOCK_ROUTING',
          route: '/pharmacy/stock-routing',
        },
      );
    }

    if (this.hasAnyRole(['ROLE_HOSPITAL_ADMIN', 'ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'ROLE_DOCTOR'])) {
      items.push({
        icon: 'handshake',
        label: 'Consent Management',
        translationKey: 'NAV.CONSENT_MANAGEMENT',
        route: '/consent-management',
      });
    }
    if (this.hasAnyRole(['ROLE_LAB_DIRECTOR', 'ROLE_QUALITY_MANAGER'])) {
      if (!items.some((i) => i.route === '/consent-management')) {
        items.push({
          icon: 'handshake',
          label: 'Consent Management',
          translationKey: 'NAV.CONSENT_MANAGEMENT',
          route: '/consent-management',
        });
      }
    }
    if (
      this.hasAnyRole([
        'ROLE_LAB_SCIENTIST',
        'ROLE_LAB_MANAGER',
        'ROLE_LAB_DIRECTOR',
        'ROLE_QUALITY_MANAGER',
        'ROLE_SUPER_ADMIN',
      ])
    ) {
      items.push({
        icon: 'approval',
        label: 'Lab Approval Queue',
        translationKey: 'NAV.LAB_APPROVAL_QUEUE',
        route: '/lab-approval-queue',
      });
    }
    if (
      this.hasAnyRole([
        'ROLE_LAB_MANAGER',
        'ROLE_LAB_DIRECTOR',
        'ROLE_QUALITY_MANAGER',
        'ROLE_HOSPITAL_ADMIN',
        'ROLE_ADMIN',
        'ROLE_SUPER_ADMIN',
      ])
    ) {
      items.push(
        {
          icon: 'monitoring',
          label: 'QC Dashboard',
          translationKey: 'NAV.QC_DASHBOARD',
          route: '/lab-qc-dashboard',
        },
        {
          icon: 'insert_chart',
          label: 'Ops Dashboard',
          translationKey: 'NAV.OPS_DASHBOARD',
          route: '/lab-ops-dashboard',
        },
      );
    }
    if (
      this.hasAnyRole([
        'ROLE_LAB_MANAGER',
        'ROLE_LAB_SCIENTIST',
        'ROLE_LAB_DIRECTOR',
        'ROLE_QUALITY_MANAGER',
        'ROLE_HOSPITAL_ADMIN',
        'ROLE_SUPER_ADMIN',
      ])
    ) {
      items.push({
        icon: 'tune',
        label: 'Lab Test Config',
        translationKey: 'NAV.LAB_TEST_CONFIG',
        route: '/lab-test-config',
      });
    }
    if (
      this.hasAnyRole([
        'ROLE_RECEPTIONIST',
        'ROLE_HOSPITAL_ADMIN',
        'ROLE_ADMIN',
        'ROLE_SUPER_ADMIN',
      ])
    ) {
      items.push({
        icon: 'space_dashboard',
        label: 'Front Desk',
        translationKey: 'NAV.FRONT_DESK',
        route: '/reception',
      });
    }

    // Doctor role: hide admin/nurse-specific entries for a cleaner sidebar
    const isDoctor = this.hasAnyRole(['ROLE_DOCTOR', 'ROLE_PHYSICIAN', 'ROLE_SURGEON']);
    const doctorHiddenRoutes = isDoctor
      ? new Set(['/nurse-station', '/scheduling', '/departments', '/staff'])
      : new Set<string>();

    return items.filter(
      (item) =>
        (!item.permission || this.permissions.hasPermission(item.permission)) &&
        (!item.roles || this.hasAnyRole(item.roles)) &&
        !doctorHiddenRoutes.has(item.route),
    );
  });

  private hasAnyRole(roles: string[]): boolean {
    const activeRole = this.roleContext.activeRole;
    if (activeRole) {
      return roles.includes(activeRole);
    }
    return this.auth.hasAnyRole(roles);
  }

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
      this.notifService.connectWebSocket();
      this.notifSub = this.notifService.getNotificationStream().subscribe((n) => {
        this.recentNotifications.update((list) => [n, ...list].slice(0, 10));
        this.unreadCount.update((c) => c + 1);
      });
      this.readCountSub = this.notifService.getReadStream().subscribe(() => {
        this.unreadCount.update((c) => Math.max(0, c - 1));
      });
      this.notifService.getAllReadStream().subscribe(() => {
        this.unreadCount.set(0);
      });
    }
  }

  ngOnDestroy(): void {
    this.notifSub?.unsubscribe();
    this.readCountSub?.unsubscribe();
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

  onNotifItemClick(n: Notification): void {
    if (!n.read) {
      n.read = true;
      this.recentNotifications.update((list) => [...list]);
      this.notifService.markAsReadAndNotify(n.id);
    }
    this.closeOverlays();
    this.router.navigate(['/notifications']);
  }

  markAllNotificationsRead(): void {
    this.notifService.markAllReadAndNotify().subscribe({
      next: () => {
        this.recentNotifications.update((list) => list.map((n) => ({ ...n, read: true })));
      },
    });
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

  switchLang(lang: string): void {
    this.translate.use(lang);
    this.currentLang.set(lang);
    localStorage.setItem('lang', lang);
  }
}
