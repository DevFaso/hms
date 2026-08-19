import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { MatrixTabComponent } from './matrix-tab';
import { PoliciesTabComponent } from './policies-tab';
import { UsersTabComponent } from './users-tab';
import { CredentialsTabComponent } from './credentials-tab';
import { SecurityTabComponent } from './security-tab';

type TabKey = 'matrix' | 'policies' | 'users' | 'credentials' | 'security';

/**
 * Governance console (SUPER_ADMIN only).
 * The flat /security-policies + /security-rules controllers would also admit
 * HOSPITAL_ADMIN, but their reads are unscoped findAll() — cross-tenant — so
 * the whole console is gated to SUPER_ADMIN client-side.
 */
@Component({
  selector: 'app-admin-governance',
  standalone: true,
  imports: [
    CommonModule,
    TranslateModule,
    MatrixTabComponent,
    PoliciesTabComponent,
    UsersTabComponent,
    CredentialsTabComponent,
    SecurityTabComponent,
  ],
  templateUrl: './admin-governance.html',
  styleUrl: './admin-governance.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminGovernanceComponent {
  activeTab = signal<TabKey>('matrix');

  readonly tabs: { key: TabKey; icon: string; labelKey: string }[] = [
    { key: 'matrix', icon: 'grid_view', labelKey: 'ADMIN_GOV.TAB_MATRIX' },
    { key: 'policies', icon: 'policy', labelKey: 'ADMIN_GOV.TAB_POLICIES' },
    { key: 'users', icon: 'group_add', labelKey: 'ADMIN_GOV.TAB_USERS' },
    { key: 'credentials', icon: 'passkey', labelKey: 'ADMIN_GOV.TAB_CREDENTIALS' },
    { key: 'security', icon: 'security', labelKey: 'ADMIN_GOV.TAB_SECURITY' },
  ];

  setTab(tab: TabKey): void {
    this.activeTab.set(tab);
  }
}
