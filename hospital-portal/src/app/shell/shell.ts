import {
  Component,
  computed,
  effect,
  inject,
  signal,
  OnInit,
  OnDestroy,
  ElementRef,
  ViewChild,
  AfterViewInit,
} from '@angular/core';
import { NavigationEnd, RouterOutlet, RouterLink, RouterLinkActive, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription, filter } from 'rxjs';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { AuthService, LoginUserProfile } from '../auth/auth.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { IdleService } from '../core/idle.service';
import { NotificationService, Notification } from '../services/notification.service';
import { LockScreenComponent } from '../lock-screen/lock-screen';
import { ImpersonationBannerComponent } from '../impersonation/impersonation-banner';
import { ImpersonationService } from '../services/impersonation.service';
import { EmergencyBroadcastBannerComponent } from '../emergency/emergency-broadcast-banner';
import { EmergencyBroadcastService } from '../services/emergency-broadcast.service';
import { NavOrderService } from './nav-order.service';
import { SkipLinkComponent } from '../shared/a11y/skip-link.component';
import { clearReportedSilent403s } from '../interceptors/error.interceptor';

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
    ImpersonationBannerComponent,
    EmergencyBroadcastBannerComponent,
    TranslateModule,
    SkipLinkComponent,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class ShellComponent implements OnInit, OnDestroy, AfterViewInit {
  /**
   * v1.0 row 11: target for the skip-link and the programmatic focus
   * landing on every route change. Carries tabindex="-1" so it can
   * accept focus without polluting Tab order. See
   * docs/ui/accessibility.md §4.
   */
  @ViewChild('mainContent') protected mainContent?: ElementRef<HTMLElement>;
  private routerSub?: Subscription;
  private readonly auth = inject(AuthService);
  private readonly permissions = inject(PermissionService);
  private readonly roleContext = inject(RoleContextService);
  private readonly router = inject(Router);
  protected readonly toast = inject(ToastService);
  private readonly notifService = inject(NotificationService);
  protected readonly idle = inject(IdleService);
  private readonly navOrder = inject(NavOrderService);
  private readonly impersonation = inject(ImpersonationService);
  private readonly emergencyBroadcast = inject(EmergencyBroadcastService);
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
          icon: 'local_pharmacy',
          label: 'Pharmacy Invoices',
          translationKey: 'NAV.PHARMACY_INVOICES',
          route: '/my-pharmacy-invoices',
        },
        {
          icon: 'history',
          label: 'Visit History',
          translationKey: 'NAV.VISIT_HISTORY',
          route: '/my-visits',
        },
        {
          icon: 'groups',
          label: 'Care Team',
          translationKey: 'NAV.CARE_TEAM',
          route: '/my-care-team',
        },
        {
          icon: 'folder_shared',
          label: 'My Records',
          translationKey: 'NAV.MY_RECORDS',
          route: '/my-records',
        },
        {
          icon: 'description',
          label: 'My Documents',
          translationKey: 'NAV.MY_DOCUMENTS',
          route: '/my-documents',
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
          icon: 'family_restroom',
          label: 'Family Access',
          translationKey: 'NAV.FAMILY_ACCESS',
          route: '/my-family-access',
        },
        {
          icon: 'summarize',
          label: 'Visit Summaries',
          translationKey: 'NAV.VISIT_SUMMARIES',
          route: '/my-summaries',
        },
        {
          icon: 'description',
          label: 'Documents',
          translationKey: 'NAV.DOCUMENTS',
          route: '/my-documents',
        },
        { icon: 'chat', label: 'Messages', translationKey: 'NAV.MESSAGES', route: '/chat' },
        {
          icon: 'notifications',
          label: 'Notifications',
          translationKey: 'NAV.NOTIFICATIONS',
          route: '/my-notifications',
        },
      ];
    }

    // MVP-5: when the active role is super admin, the side-nav drops the
    // generic Dashboard entry — the Control Tower (/super-admin, appended by
    // appendSuperAdminEntry) is their landing page. Other roles still see it.
    const isActiveSuperAdmin = activeRole === 'ROLE_SUPER_ADMIN';

    const items: NavItem[] = [
      ...(isActiveSuperAdmin
        ? []
        : [
            {
              icon: 'dashboard',
              label: 'Dashboard',
              translationKey: 'NAV.DASHBOARD',
              route: '/dashboard',
            },
          ]),
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
        icon: 'medication_liquid',
        label: 'Medication History',
        translationKey: 'NAV.MEDICATION_HISTORY',
        route: '/medication-history',
        roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_PHARMACIST', 'ROLE_LAB_SCIENTIST'],
      },
      {
        icon: 'surgical',
        label: 'Procedure Orders',
        translationKey: 'NAV.PROCEDURE_ORDERS',
        route: '/procedure-orders',
        roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'],
      },
      {
        icon: 'how_to_reg',
        label: 'Registrations',
        translationKey: 'NAV.REGISTRATIONS',
        route: '/registrations',
        roles: [
          'ROLE_RECEPTIONIST',
          'ROLE_HOSPITAL_ADMIN',
          'ROLE_DOCTOR',
          'ROLE_NURSE',
          'ROLE_MIDWIFE',
          'ROLE_SUPER_ADMIN',
        ],
      },
      {
        icon: 'exit_to_app',
        label: 'Discharge',
        translationKey: 'NAV.DISCHARGE',
        route: '/discharge',
        roles: [
          'ROLE_DOCTOR',
          'ROLE_NURSE',
          'ROLE_MIDWIFE',
          'ROLE_HOSPITAL_ADMIN',
          'ROLE_SUPER_ADMIN',
        ],
      },
      {
        icon: 'school',
        label: 'Patient Education',
        translationKey: 'NAV.PATIENT_EDUCATION',
        route: '/patient-education',
        roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_MIDWIFE', 'ROLE_SUPER_ADMIN'],
      },
      {
        icon: 'pregnant_woman',
        label: 'Maternity',
        translationKey: 'NAV.MATERNITY',
        route: '/maternity',
        roles: [
          'ROLE_DOCTOR',
          'ROLE_NURSE',
          'ROLE_MIDWIFE',
          'ROLE_RECEPTIONIST',
          'ROLE_HOSPITAL_ADMIN',
          'ROLE_SUPER_ADMIN',
        ],
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

    // Super-Admin control tower (visible only to ROLE_SUPER_ADMIN)
    this.appendSuperAdminEntry(items);

    // Admin items — gated individually so HOSPITAL_ADMIN sees relevant ones
    if (this.permissions.hasPermission('View Hospitals')) {
      items.push({
        icon: 'local_hospital',
        label: 'Hospitals',
        translationKey: 'NAV.HOSPITALS',
        route: '/hospitals',
      });
    }
    // PR #225 review: gate the admin-items group via the active-role-aware
    // helper so a multi-role super admin who picked a non-super activeRole
    // doesn't see entries that the corresponding RoleGuard would 403 on.
    // (`permissions.hasPermission('*')` reads JWT roles, not the active role,
    // and the route gates already require ROLE_ADMIN / ROLE_SUPER_ADMIN.)
    if (this.hasAnyRole(['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'])) {
      items.push(
        {
          icon: 'corporate_fare',
          label: 'Organizations',
          translationKey: 'NAV.ORGANIZATIONS',
          route: '/organizations',
        },
        { icon: 'manage_accounts', label: 'Users', translationKey: 'NAV.USERS', route: '/users' },
        { icon: 'shield', label: 'Roles', translationKey: 'NAV.ROLES', route: '/roles' },
        // MVP-5b: super-admin lands on the namespaced /super-admin/platform alias
        // so the active-link highlight matches the address bar after the rewrite
        // guard runs. ADMIN keeps the legacy /platform path.
        {
          icon: 'hub',
          label: 'Platform',
          translationKey: 'NAV.PLATFORM',
          route: isActiveSuperAdmin ? '/super-admin/platform' : '/platform',
        },
      );
      // MVP-5: only ROLE_ADMIN sees Administration. Super admins are
      // redirected from /admin to /super-admin (SuperAdminRedirectGuard) and
      // already have the Control Tower in their nav.
      if (!isActiveSuperAdmin) {
        items.push({
          icon: 'admin_panel_settings',
          label: 'Administration',
          translationKey: 'NAV.ADMINISTRATION',
          route: '/admin',
        });
      }
      items.push(
        {
          icon: 'query_stats',
          label: 'Analytics',
          translationKey: 'NAV.ANALYTICS',
          route: '/analytics',
        },
        {
          icon: 'toggle_on',
          label: 'Feature Flags',
          translationKey: 'NAV.FEATURE_FLAGS',
          route: '/feature-flags',
        },
      );
    }
    if (this.hasAnyRole(['ROLE_DOCTOR', 'ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'])) {
      items.push({
        icon: 'draw',
        label: 'Digital Signatures',
        translationKey: 'NAV.DIGITAL_SIGNATURES',
        route: '/digital-signatures',
      });
    }
    // Assignment admin — SecurityConfig gates /assignments/** to these two roles
    if (this.hasAnyRole(['ROLE_HOSPITAL_ADMIN', 'ROLE_SUPER_ADMIN'])) {
      items.push({
        icon: 'assignment_ind',
        label: 'Assignments',
        translationKey: 'NAV.ASSIGNMENTS',
        route: '/admin-assignments',
      });
    }
    // Governance console — SUPER_ADMIN-only backends (matrix, /super-admin/**)
    if (this.hasAnyRole(['ROLE_SUPER_ADMIN'])) {
      items.push({
        icon: 'gavel',
        label: 'Governance',
        translationKey: 'NAV.GOVERNANCE',
        route: '/admin-governance',
      });
    }
    if (this.permissions.hasPermission('View Audit Logs')) {
      items.push({
        icon: 'policy',
        label: 'Audit Logs',
        translationKey: 'NAV.AUDIT_LOGS',
        // MVP-5b: same rationale as Platform — super admins use the
        // /super-admin/audit-logs alias; HOSPITAL_ADMIN/ADMIN keep the
        // legacy /audit-logs path.
        route: isActiveSuperAdmin ? '/super-admin/audit-logs' : '/audit-logs',
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
        'ROLE_LAB_DIRECTOR',
        'ROLE_LAB_MANAGER',
        'ROLE_HOSPITAL_ADMIN',
        'ROLE_ADMIN',
        'ROLE_SUPER_ADMIN',
      ])
    ) {
      items.push({
        icon: 'badge',
        label: 'Lab Staff',
        translationKey: 'NAV.LAB_STAFF',
        route: '/lab-staff',
      });
    }
    if (
      this.hasAnyRole([
        'ROLE_LAB_DIRECTOR',
        'ROLE_LAB_MANAGER',
        'ROLE_LAB_TECHNICIAN',
        'ROLE_HOSPITAL_ADMIN',
        'ROLE_SUPER_ADMIN',
      ])
    ) {
      items.push(
        {
          icon: 'precision_manufacturing',
          label: 'Lab Instruments',
          translationKey: 'NAV.LAB_INSTRUMENTS',
          route: '/lab-instruments',
        },
        {
          icon: 'inventory_2',
          label: 'Lab Inventory',
          translationKey: 'NAV.LAB_INVENTORY',
          route: '/lab-inventory',
        },
      );
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

  private appendSuperAdminEntry(items: NavItem[]): void {
    // Use the same hasAnyRole helper the rest of baseNavItems relies on so the
    // sidebar respects the role the user picked at login. Using the raw role
    // list (isSuperAdmin) would surface this entry for a multi-role user who
    // selected a non-super active role and route them straight to /error/403.
    if (!this.hasAnyRole(['ROLE_SUPER_ADMIN'])) return;
    items.push({
      icon: 'admin_panel_settings',
      label: 'Super Admin',
      translationKey: 'NAV.SUPER_ADMIN',
      route: '/super-admin',
    });
    items.push({
      icon: 'cable',
      label: 'Integrations Health',
      translationKey: 'NAV.INTEGRATIONS_HEALTH',
      route: '/super-admin/integrations',
    });
    items.push({
      icon: 'policy',
      label: 'Audit Search',
      translationKey: 'NAV.AUDIT_SEARCH',
      route: '/super-admin/audit-search',
    });
    items.push({
      icon: 'emergency',
      label: 'Emergency Controls',
      translationKey: 'NAV.EMERGENCY_CONTROLS',
      route: '/super-admin/emergency',
    });
    items.push({
      icon: 'subscriptions',
      label: 'Subscriptions',
      translationKey: 'NAV.SUBSCRIPTIONS',
      route: '/super-admin/subscriptions',
    });
    items.push({
      icon: 'public',
      label: 'Data Residency',
      translationKey: 'NAV.DATA_RESIDENCY',
      route: '/super-admin/data-residency',
    });
  }

  /**
   * User-reordered nav items.
   * Rebuilt whenever the permission-filtered base list changes (e.g. when the
   * backend permission set arrives), with any saved order re-applied on top.
   */
  navItems = signal<NavItem[]>([]);

  constructor() {
    effect(() => {
      const base = this.baseNavItems();
      const saved = this.navOrder.load();
      this.navItems.set(saved ? this.navOrder.applyOrder(base, saved) : base);
    });
  }

  ngOnInit(): void {
    this.userProfile.set(this.auth.getUserProfile());
    this.permissions.loadFromBackend();

    this.loadNotifications();
    this.idle.start();

    // MVP-4: hydrate the impersonation banner state on every shell mount so a
    // page refresh while impersonating re-paints the banner instead of
    // silently dropping it.
    this.impersonation.refreshActive().subscribe({ error: () => undefined });

    // MVP-7b: subscribe to /topic/emergency-broadcast so a super-admin
    // broadcast surfaces in the banner across every authenticated route.
    this.emergencyBroadcast.connect();

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

  /**
   * v1.0 row 11: on every successful navigation, move focus to the
   * `<main id="main-content">` element. Without this, keyboard and
   * screen-reader users stay on whatever they clicked (a sidebar nav
   * link, a button in a list) and lose context. Angular does NOT do
   * this by default. See docs/ui/accessibility.md §4.
   */
  ngAfterViewInit(): void {
    this.routerSub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(() => {
        // A successful navigation proves the chunk graph is healthy — re-arm
        // the one-shot stale-chunk reload guard (see app.config.ts).
        sessionStorage.removeItem('hms-chunk-reload');
        // queueMicrotask defers until after the new component has
        // rendered into the outlet so focus actually lands on the
        // freshly painted <main>, not the previous one.
        queueMicrotask(() => this.mainContent?.nativeElement.focus({ preventScroll: true }));
      });
  }

  ngOnDestroy(): void {
    this.notifSub?.unsubscribe();
    this.readCountSub?.unsubscribe();
    this.routerSub?.unsubscribe();
    this.notifService.disconnectWebSocket();
    // MVP-7b — release the emergency-broadcast STOMP socket on shell teardown.
    this.emergencyBroadcast.disconnect();
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
    this.moveNavItem(fromIndex, dropIndex);
    this.resetDrag();
  }

  onDragEnd(): void {
    this.resetDrag();
  }

  private resetDrag(): void {
    this.dragIndex.set(null);
    this.dragOverIndex.set(null);
  }

  /**
   * Shared reorder primitive used by both the mouse drag-drop and the
   * Alt+ArrowUp/Down keyboard alternative. Mutates the navItems signal
   * and persists the new order to localStorage via NavOrderService.
   * Caller is responsible for any focus restoration.
   */
  private moveNavItem(fromIndex: number, toIndex: number): void {
    this.navItems.update((items) => {
      const reordered = [...items];
      const [moved] = reordered.splice(fromIndex, 1);
      reordered.splice(toIndex, 0, moved);
      this.navOrder.save(reordered.map((i) => i.route));
      return reordered;
    });
  }

  /**
   * v1.0 row 11 finish — keyboard alternative to sidebar drag-reorder.
   *
   * `Alt+ArrowUp` swaps the focused nav item with the one above;
   * `Alt+ArrowDown` swaps it with the one below. The shortcut carries
   * a modifier so it doesn't collide with AZERTY key positions
   * (docs/ui/accessibility.md §5). After the swap, focus is restored
   * to the moved item at its new position so a clinician can keep
   * pressing the shortcut to walk the item across multiple slots.
   *
   * No-op (returns without preventDefault) when:
   * - Alt is not held — let the browser handle the keystroke normally.
   * - The arrow direction would push the item past either end —
   *   silently clamped so the user doesn't get a jarring blocked
   *   keystroke. Screen readers re-announce on the next focus change.
   */
  onNavKeydown(event: KeyboardEvent, index: number): void {
    if (!event.altKey) return;
    let toIndex: number | null = null;
    if (event.key === 'ArrowUp') toIndex = index - 1;
    else if (event.key === 'ArrowDown') toIndex = index + 1;
    if (toIndex === null) return;

    const items = this.navItems();
    if (toIndex < 0 || toIndex >= items.length) {
      // Clamp at the ends. preventDefault still — we don't want the
      // browser scrolling the viewport while the user holds Alt+Arrow.
      event.preventDefault();
      return;
    }

    event.preventDefault();
    event.stopPropagation();

    // Capture the moved item's route BEFORE the swap. After the
    // signal mutation Angular re-renders the @for, but the
    // ViewChildren QueryList is keyed by encounter order, not DOM
    // order — reading it post-swap and indexing into `toIndex` lands
    // on a stale ElementRef whose underlying DOM node is no longer
    // at that position. Querying by `routerLink` after the next
    // animation frame is robust to whichever way Angular chooses to
    // reuse / recreate nodes.
    const movingItem = this.navItems()[index];

    this.moveNavItem(index, toIndex);

    // requestAnimationFrame defers past Angular's CD pass. queueMicrotask
    // is too early — the new DOM hasn't been painted yet, and
    // focus() against a detached node falls back to <body>. The first
    // attempt of this handler used queueMicrotask + a ViewChildren
    // index lookup; both were fragile under Angular signals where
    // change detection isn't synchronous with the signal write.
    requestAnimationFrame(() => {
      const escapedRoute = CSS.escape(movingItem.route);
      const moved = document.querySelector<HTMLAnchorElement>(
        `.sidebar-nav .nav-item[href$="${escapedRoute}"]`,
      );
      moved?.focus({ preventScroll: false });
    });
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
    this.permissions.clear();
    clearReportedSilent403s();
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
    this.permissions.clear();
    clearReportedSilent403s();
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
