import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { RoleContextService } from '../core/role-context.service';

/** The super-admin's two audit screens, in the order the tabs show them. */
export const SUPER_ADMIN_AUDIT_TABS: readonly { route: string; labelKey: string }[] = [
  { route: '/super-admin/audit-logs', labelKey: 'PLATFORM_SETUP.AUDIT_TABS.LOG' },
  { route: '/super-admin/audit-search', labelKey: 'PLATFORM_SETUP.AUDIT_TABS.SEARCH' },
];

/**
 * QA r65 D2777: one « audit » entry in the super-admin's sidebar instead of
 * two overlapping ones (« Journaux d'audit » and « Recherche d'audit », each
 * with its own filters and no way from one to the other). The sidebar keeps
 * the log; both pages carry these two tabs, so the search is one click away
 * and the reader sees they are two views of the same trail.
 *
 * <p>Rendered only when the acting role is the super-admin: the log page is
 * also the hospital admin's (/audit-logs), who has no search screen.
 */
@Component({
  selector: 'app-audit-tabs',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (show()) {
      <nav
        class="audit-tabs"
        data-testid="audit-tabs"
        [attr.aria-label]="'PLATFORM_SETUP.AUDIT_TABS.LABEL' | translate"
      >
        @for (tab of tabs; track tab.route) {
          <a
            class="audit-tab"
            [routerLink]="tab.route"
            routerLinkActive="audit-tab-active"
            ariaCurrentWhenActive="page"
          >
            {{ tab.labelKey | translate }}
          </a>
        }
      </nav>
    }
  `,
  styles: [
    `
      .audit-tabs {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        margin: 0 0 16px;
        border-bottom: 1px solid var(--border);
      }
      .audit-tab {
        padding: 8px 14px;
        border-bottom: 2px solid transparent;
        margin-bottom: -1px;
        font-size: 0.9375rem;
        font-weight: 600;
        color: var(--text-secondary);
        text-decoration: none;
      }
      .audit-tab-active {
        color: var(--primary-dark);
        border-bottom-color: var(--primary);
      }
    `,
  ],
})
export class AuditTabsComponent {
  private readonly roleContext = inject(RoleContextService);

  protected readonly tabs = SUPER_ADMIN_AUDIT_TABS;

  /** The acting role, not merely a held one: a super-admin acting as a doctor has no such pages. */
  protected readonly show = computed(() => {
    const active = this.roleContext.activeRole;
    return active ? active === 'ROLE_SUPER_ADMIN' : this.roleContext.isSuperAdmin();
  });
}
