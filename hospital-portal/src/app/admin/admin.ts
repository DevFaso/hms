import { Component, inject, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';

import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { AuthService } from '../auth/auth.service';
import { DashboardService } from '../services/dashboard.service';

interface SystemStatBase {
  /** An i18n key, never text — see ADMIN.STAT.*. */
  labelKey: string;
  icon: string;
  color: string;
}

/**
 * A card shows either a figure or a word, never both. Expressed as a union so
 * the exclusivity is checked rather than remembered: the earlier shape made
 * `value` required and `valueKey` optional, which forced a dead `value: ''` on
 * every word-valued card.
 */
type SystemStat =
  | (SystemStatBase & { value: string | number; valueKey?: never })
  | (SystemStatBase & { valueKey: string; value?: never });

interface AdminSection {
  titleKey: string;
  descriptionKey: string;
  icon: string;
  route: string;
  color: string;
}

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  templateUrl: './admin.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './admin.scss',
})
export class AdminComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);
  private readonly auth = inject(AuthService);

  loading = signal(true);

  /**
   * The skeleton names the same four cards the loaded state does, so the
   * labels do not swap under the reader when the summary arrives.
   */
  stats = signal<SystemStat[]>([
    { labelKey: 'ADMIN.STAT.ACTIVE_STAFF', value: '—', icon: 'badge', color: '#10b981' },
    { labelKey: 'ADMIN.STAT.ON_SHIFT_TODAY', value: '—', icon: 'schedule', color: '#23b79c' },
    {
      labelKey: 'ADMIN.STAT.TODAY_APPOINTMENTS',
      value: '—',
      icon: 'calendar_month',
      color: '#8b5cf6',
    },
    { labelKey: 'ADMIN.STAT.SYSTEM_HEALTH', value: '—', icon: 'monitor_heart', color: '#6b7280' },
  ]);

  // Role audit decision C1: only ROLE_ADMIN can reach this page (super
  // admins are redirected to the Control Tower), so every tile must be a
  // surface whose route guard AND backend admit ADMIN. The old set sent
  // admins into /staff, /departments, /billing and /lab — all-403 pages —
  // and the Audit Logs tile pointed back at /admin itself.
  sections: AdminSection[] = [
    {
      titleKey: 'ADMIN.SECTION.USERS_TITLE',
      descriptionKey: 'ADMIN.SECTION.USERS_DESC',
      icon: 'manage_accounts',
      route: '/users',
      color: '#23b79c',
    },
    {
      titleKey: 'ADMIN.SECTION.TRACKER_TITLE',
      descriptionKey: 'ADMIN.SECTION.TRACKER_DESC',
      icon: 'view_kanban',
      route: '/patient-tracker',
      color: '#8b5cf6',
    },
    {
      titleKey: 'ADMIN.SECTION.FRONT_DESK_TITLE',
      descriptionKey: 'ADMIN.SECTION.FRONT_DESK_DESC',
      icon: 'support_agent',
      route: '/reception',
      color: '#10b981',
    },
    {
      titleKey: 'ADMIN.SECTION.NOTIFICATIONS_TITLE',
      descriptionKey: 'ADMIN.SECTION.NOTIFICATIONS_DESC',
      icon: 'notifications_active',
      route: '/notifications',
      color: '#ef4444',
    },
    {
      titleKey: 'ADMIN.SECTION.AUDIT_TITLE',
      descriptionKey: 'ADMIN.SECTION.AUDIT_DESC',
      icon: 'shield',
      route: '/audit-logs',
      color: '#64748b',
    },
  ];

  ngOnInit(): void {
    this.loadStats();
  }

  loadStats(): void {
    this.loading.set(true);
    // Role audit decision C1: this page is reachable ONLY by ROLE_ADMIN
    // (super admins get redirected to /super-admin), yet it called the
    // SUPER_ADMIN-only /super-admin/summary — a guaranteed 403 leaving the
    // stat cards at "—" forever. The hospital-admin summary admits ADMIN.
    this.dashboardService.getHospitalAdminSummary().subscribe({
      next: (data) => {
        this.stats.set([
          {
            labelKey: 'ADMIN.STAT.ACTIVE_STAFF',
            value: data.staffing?.activeStaff ?? '—',
            icon: 'badge',
            color: '#10b981',
          },
          {
            labelKey: 'ADMIN.STAT.ON_SHIFT_TODAY',
            value: data.staffing?.onShiftToday ?? '—',
            icon: 'schedule',
            color: '#23b79c',
          },
          {
            labelKey: 'ADMIN.STAT.TODAY_APPOINTMENTS',
            value: data.appointments?.todayTotal ?? '—',
            icon: 'calendar_month',
            color: '#8b5cf6',
          },
          {
            labelKey: 'ADMIN.STAT.SYSTEM_HEALTH',
            valueKey: 'ADMIN.STAT.ONLINE',
            icon: 'monitor_heart',
            color: '#059669',
          },
        ]);
        this.loading.set(false);
      },
      // The health card used to read "Online" here too — a constant, shown
      // green precisely when the call that would have contradicted it had
      // just failed. It now reports what actually happened.
      error: () => {
        this.stats.update((cards) =>
          cards.map((card) =>
            card.labelKey === 'ADMIN.STAT.SYSTEM_HEALTH'
              ? {
                  labelKey: card.labelKey,
                  valueKey: 'ADMIN.STAT.UNAVAILABLE',
                  icon: 'monitor_heart',
                  color: '#dc2626',
                }
              : card,
          ),
        );
        this.loading.set(false);
      },
    });
  }

  get userName(): string {
    const profile = this.auth.getUserProfile();
    return profile ? `${profile.firstName} ${profile.lastName}` : 'Admin';
  }
}
