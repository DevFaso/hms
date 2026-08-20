import { ApplicationConfig, provideAppInitializer, inject } from '@angular/core';
import { provideRouter, withNavigationErrorHandler, NavigationError } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { OidcAuthService } from './auth/oidc-auth.service';
import { apiPrefixInterceptor } from './interceptors/auth.interceptor';
import { csrfInterceptor } from './interceptors/csrf.interceptor';
import { errorInterceptor } from './interceptors/error.interceptor';
import { offlineDispenseInterceptor } from './interceptors/offline-dispense.interceptor';

/**
 * Recover from stale-deployment chunk failures. After a redeploy replaces the
 * hashed lazy chunks, a browser tab still running the previous session gets
 * `Failed to fetch dynamically imported module: .../chunk-XXXX.js` on the next
 * lazy navigation and the route silently dies. One full reload fetches the new
 * index.html + chunk graph and replays the navigation. The sessionStorage
 * guard (cleared on success) prevents a reload loop when the chunk is missing
 * for a different reason (e.g. broken deploy).
 */
const CHUNK_RELOAD_GUARD = 'hms-chunk-reload';

export function handleChunkLoadNavigationError(error: NavigationError): void {
  const message = String((error.error as Error | undefined)?.message ?? error.error ?? '');
  const isChunkLoadFailure =
    /Failed to fetch dynamically imported module|Importing a module script failed|ChunkLoadError/i.test(
      message,
    );
  if (!isChunkLoadFailure || typeof window === 'undefined') return;

  if (window.sessionStorage.getItem(CHUNK_RELOAD_GUARD)) {
    console.error('Chunk load failed again after reload — deploy may be broken:', message);
    return;
  }
  window.sessionStorage.setItem(CHUNK_RELOAD_GUARD, '1');
  window.location.assign(error.url);
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withNavigationErrorHandler(handleChunkLoadNavigationError)),
    provideHttpClient(
      withInterceptors([
        apiPrefixInterceptor,
        csrfInterceptor,
        // Roadmap row 4 / T-68 — must run BEFORE errorInterceptor so a queued
        // (synthetic 202) response is not treated as a real error and routed
        // through the auth-refresh / toast path. Order is the chain order.
        offlineDispenseInterceptor,
        errorInterceptor,
      ]),
    ),
    provideTranslateService({
      defaultLanguage: 'fr',
      fallbackLang: 'en',
    }),
    provideTranslateHttpLoader({ prefix: './assets/i18n/', suffix: '.json' }),
    // KC-2b: Keycloak OIDC PKCE login. Bootstraps the OAuth client and
    // attempts to complete a redirect-back code exchange on app start.
    // Becomes a no-op when `environment.oidc.enabled` is false, so legacy
    // form-based login keeps working untouched during the rollout.
    provideOAuthClient(),
    provideAppInitializer(() => inject(OidcAuthService).initialize()),
  ],
};
