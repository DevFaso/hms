import { ChunkRecoveryService } from './core/chunk-recovery.service';
import { Component, inject, OnInit, ChangeDetectionStrategy } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from './auth/auth.service';
import { RoleContextService } from './core/role-context.service';
import { SessionScopeService } from './core/session-scope.service';
import { AnalyticsService } from './core/services/analytics.service';
import { environment } from '../environments/environment';
import { DEFAULT_LANG, storedLang } from './shared/i18n/app-locale';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, TranslateModule],
  template: `
    @if (chunkRecovery.failedUrl()) {
      <section class="chunk-recovery" role="alert" aria-labelledby="chunk-recovery-title">
        <h1 id="chunk-recovery-title">{{ 'CHUNK_RECOVERY.TITLE' | translate }}</h1>
        <p>{{ 'CHUNK_RECOVERY.MESSAGE' | translate }}</p>
        <button type="button" (click)="chunkRecovery.reload()">
          {{ 'CHUNK_RECOVERY.RELOAD' | translate }}
        </button>
      </section>
    }
    <router-outlet />
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .chunk-recovery {
        position: relative;
        z-index: 100;
        margin: 18px;
        padding: 22px;
        border: 1px solid #e3bd7b;
        border-radius: 12px;
        background: #fff9ed;
        color: #593a10;
      }
      .chunk-recovery h1 {
        margin: 0 0 10px;
        font-size: 20px;
      }
      .chunk-recovery p {
        line-height: 1.6;
      }
      .chunk-recovery button {
        border: 0;
        border-radius: 7px;
        background: #0b7061;
        color: white;
        padding: 12px 18px;
        cursor: pointer;
      }
      .chunk-recovery button:focus-visible {
        outline: 3px solid #087269;
        outline-offset: 3px;
      }
      :host {
        display: block;
        height: 100%;
      }
    `,
  ],
})
export class AppComponent implements OnInit {
  title = 'hospital-portal';
  readonly chunkRecovery = inject(ChunkRecoveryService);

  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly sessionScope = inject(SessionScopeService);
  private readonly http = inject(HttpClient);
  private readonly translate = inject(TranslateService);
  private readonly analytics = inject(AnalyticsService);

  ngOnInit(): void {
    this.translate.setDefaultLang(DEFAULT_LANG);
    const lang = storedLang();
    this.translate.use(lang);
    document.documentElement.lang = lang;
    this.analytics.init();

    // Bootstrap the XSRF-TOKEN cookie from the server so that the custom
    // CSRF interceptor can attach X-XSRF-TOKEN on subsequent mutating requests.
    // This is a fire-and-forget GET; errors are intentionally swallowed because
    // the app can function (degraded CSRF protection) even if the backend is
    // temporarily unreachable during bootstrap.
    this.http
      .get<void>(`${environment.apiBase}/auth/csrf-token`, { observe: 'response' })
      .subscribe({
        error: (_err: unknown) => {
          /* intentionally ignored — degraded CSRF protection on network failure */
        },
      });

    // Re-hydrate the session on every app bootstrap (hard refresh, direct
    // URL, tab re-open). E9 #55b: the roles come from the token synchronously
    // so the route guards can run at once; the hospital scope comes from the
    // live session. The stored profile — the last thing the server said —
    // fills the gap until the server replies, and stays if it cannot.
    const token = this.auth.getToken();
    if (token && !this.auth.isExpired(token)) {
      this.roleContext.setRoles(this.auth.getRoles());
      this.sessionScope.applyStoredProfile();
      this.sessionScope.hydrate().subscribe();
    }
  }
}
