import { Component, ChangeDetectionStrategy, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { RoleLabelPipe } from '../shared/pipes/role-label.pipe';

@Component({
  selector: 'app-error-403',
  standalone: true,
  imports: [RouterLink, TranslateModule, RoleLabelPipe],
  template: `
    <div class="error-page">
      <span class="material-symbols-outlined error-icon" aria-hidden="true">lock</span>
      <p class="error-code" aria-hidden="true">403</p>
      <h1>{{ 'ERROR_403.TITLE' | translate }}</h1>
      <p class="error-body">{{ 'ERROR_403.BODY' | translate }}</p>
      @if (betterRole; as better) {
        <p class="role-hint" role="note">
          {{
            'ERROR_403.ROLE_HINT'
              | translate: { active: activeRole | roleLabel, better: better | roleLabel }
          }}
        </p>
        <button type="button" class="btn-secondary relogin-btn" (click)="signInAgain()">
          {{ 'ERROR_403.SIGN_IN_AGAIN' | translate }}
        </button>
      }
      <a routerLink="/dashboard" class="btn-primary">
        <span class="material-symbols-outlined" aria-hidden="true">arrow_back</span>
        {{ 'ERROR_403.BACK' | translate }}
      </a>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .error-page {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      min-height: 60vh;
      text-align: center;
      padding: 2rem;
    }
    .error-icon {
      font-size: 64px;
      color: var(--danger);
      margin-bottom: 1rem;
    }
    .error-code {
      font-size: 4rem;
      font-weight: 800;
      line-height: 1;
      color: var(--text-primary);
      margin: 0 0 0.5rem;
    }
    h1 {
      color: var(--text-primary);
      margin: 0 0 0.5rem;
    }
    .error-body {
      max-width: 34rem;
      color: var(--text-secondary);
      margin: 0 0 2rem;
    }
    .role-hint {
      max-width: 36rem;
      color: #1e293b;
      background: var(--primary-soft);
      border-radius: 8px;
      padding: 0.75rem 1rem;
      margin: 0 0 1rem;
    }
    .relogin-btn {
      margin-bottom: 1rem;
    }
  `,
})
export class Error403Component {
  private readonly roleContext = inject(RoleContextService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly activeRole = this.roleContext.activeRole ?? '';
  /** Another role this account holds that would open the refused page, if any. */
  readonly betterRole = this.roleContext.roleThatWouldAllow();

  signInAgain(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }
}
