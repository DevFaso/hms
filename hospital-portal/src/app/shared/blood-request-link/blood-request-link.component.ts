import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { actionRoles } from '../../core/action-permissions';
import { RoleContextService } from '../../core/role-context.service';
import { bloodRequestState } from '../blood-request-handoff';

/**
 * QA D3220 (owner priority): « Demander du sang » beside the blood group, where the bleeding is seen (the
 * labour and delivery card and its haemorrhage alert, the post-partum watch, the prescription dialog). It
 * opens the blood bank's existing request form with this patient already picked; nothing about the request
 * is decided here. Offered only to the roles `TransfusionController#createRequest` admits, so nobody is
 * sent to a form the API refuses.
 */
@Component({
  selector: 'app-blood-request-link',
  standalone: true,
  imports: [RouterLink, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (patientId() && canRequest) {
      <a
        class="btn-secondary blood-request-link"
        data-testid="blood-request-link"
        routerLink="/transfusions"
        [state]="state()"
      >
        <span class="material-symbols-outlined" aria-hidden="true">bloodtype</span>
        {{ 'TRANSFUSION.NEW_REQUEST' | translate }}
      </a>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
    }
    .blood-request-link {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      text-decoration: none;
      white-space: nowrap;
    }
  `,
})
export class BloodRequestLinkComponent {
  readonly patientId = input<string | null | undefined>(null);

  protected readonly canRequest = inject(RoleContextService).hasAnyActiveRole(
    actionRoles('transfusionRequestCreate'),
  );
  protected readonly state = computed(() => bloodRequestState(this.patientId() ?? ''));
}
