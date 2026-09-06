import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { RoleContextService } from '../../core/role-context.service';

/**
 * The empty state of a page whose data belongs to one facility while no
 * hospital is pinned: a super-admin in global view (or, rarely, a staff
 * account with no active hospital yet). Rendered next to the scope chip so
 * the fix is one click away; renders nothing once a scope exists.
 */
@Component({
  selector: 'app-hospital-scope-hint',
  standalone: true,
  imports: [TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!hasHospitalScope()) {
      <p class="scope-hint" role="status" data-testid="scope-hint">
        {{ 'HOSPITAL_SCOPE.PICK_TO_CONTINUE' | translate }}
      </p>
    }
  `,
  styles: [
    `
      .scope-hint {
        margin: 1rem 0;
        padding: 0.875rem 1rem;
        border-radius: 8px;
        background: var(--primary-soft);
        border-left: 4px solid var(--primary);
        font-size: 0.9375rem;
      }
    `,
  ],
})
export class HospitalScopeHintComponent {
  private readonly roleContext = inject(RoleContextService);
  protected readonly hasHospitalScope = this.roleContext.hasHospitalScope;
}
