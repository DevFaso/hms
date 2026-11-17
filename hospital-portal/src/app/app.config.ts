import { ChunkRecoveryService } from './core/chunk-recovery.service';
import { installDecimalCommaSupport } from './shared/decimal-comma';
import { installFocusRescue } from './shared/a11y/focus-rescue';
import {
  installServerFieldMarks,
  serverFieldErrorsInterceptor,
} from './shared/forms/server-field-errors';
import { ApplicationConfig, LOCALE_ID, provideAppInitializer, inject } from '@angular/core';
import { DOCUMENT, registerLocaleData } from '@angular/common';
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
import { patientChartChangeInterceptor } from './interceptors/patient-chart-change.interceptor';
import { currentLocale } from './shared/i18n/app-locale';

export function handleChunkLoadNavigationError(error: NavigationError): void {
  inject(ChunkRecoveryService).handle(error);
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
        // QA D1341 / D1320 / D1321: a write refused with `fieldErrors` marks
        // the named fields in the form it was sent from (outermost, so it sees
        // the refusal the page sees, after CSRF replays and the error path).
        serverFieldErrorsInterceptor,
        apiPrefixInterceptor,
        languageInterceptor,
        csrfInterceptor,
        hospitalCacheInterceptor,
        // QA D957: a write on a patient's chart refreshes every summary of it.
        patientChartChangeInterceptor,
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
    // QA D505: comma decimals in every numeric input, whatever the browser locale.
    provideAppInitializer(() => installDecimalCommaSupport(inject(DOCUMENT))),
    // QA D1202 / D1198 / D1199 / D1197: focus never falls to the page body when
    // the control that held it goes away (shared/a11y/focus-rescue.ts).
    provideAppInitializer(() => installFocusRescue(inject(DOCUMENT))),
    // QA D1341: remember where the reader acts, so a refused write's fields
    // are marked in that form (shared/forms/server-field-errors.ts).
    provideAppInitializer(() => installServerFieldMarks(inject(DOCUMENT))),
  ],
};
