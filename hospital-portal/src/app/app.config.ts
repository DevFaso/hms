import { ApplicationConfig, LOCALE_ID, provideAppInitializer, inject } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import localeEs from '@angular/common/locales/es';
import { provideRouter, withNavigationErrorHandler, NavigationError } from '@angular/router';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import { OidcAuthService } from './auth/oidc-auth.service';
import { apiPrefixInterceptor } from './interceptors/auth.interceptor';
import { csrfInterceptor } from './interceptors/csrf.interceptor';
import { hospitalCacheInterceptor } from './interceptors/hospital-cache.interceptor';
import { errorInterceptor } from './interceptors/error.interceptor';
import { offlineDispenseInterceptor } from './interceptors/offline-dispense.interceptor';
import { languageInterceptor } from './interceptors/language.interceptor';
import { currentLocale } from './shared/i18n/app-locale';

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

// Angular ships only en-US; every other locale's date, number and currency
// rules must be registered or `| date` throws for it. French is the product
// language and Spanish the third UI language.
registerLocaleData(localeFr, 'fr');
registerLocaleData(localeEs, 'es');

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withNavigationErrorHandler(handleChunkLoadNavigationError)),
    provideHttpClient(
      withXhr(),
      withInterceptors([
        apiPrefixInterceptor,
        languageInterceptor,
        csrfInterceptor,
        hospitalCacheInterceptor,
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
    // ngx-translate owns the strings; this owns the dates, numbers and
    // currency behind `| date`, `| number` and `| currency`. Without it
    // Angular's built-in en-US rendered "Sep 13, 2026" on a French screen.
    // Read once at bootstrap — a language switch reloads (see applyLanguage).
    { provide: LOCALE_ID, useFactory: currentLocale },
    provideTranslateHttpLoader({ prefix: './assets/i18n/', suffix: '.json' }),
    // KC-2b: Keycloak OIDC PKCE login. Bootstraps the OAuth client and
    // attempts to complete a redirect-back code exchange on app start.
    // Becomes a no-op when `environment.oidc.enabled` is false, so legacy
    // form-based login keeps working untouched during the rollout.
    provideOAuthClient(),
    provideAppInitializer(() => inject(OidcAuthService).initialize()),
  ],
};
