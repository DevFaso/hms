import { ApplicationConfig } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';

import { routes } from './app.routes';
import { apiPrefixInterceptor } from './interceptors/auth.interceptor';
import { errorInterceptor } from './interceptors/error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([apiPrefixInterceptor, errorInterceptor])),
    provideAnimationsAsync(),
    provideTranslateService({
      defaultLanguage: 'en',
      fallbackLang: 'en',
    }),
    provideTranslateHttpLoader({ prefix: './assets/i18n/', suffix: '.json' }),
  ],
};
