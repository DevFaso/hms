import { TranslateService } from '@ngx-translate/core';
import { Observable, catchError, firstValueFrom, map, of, timeout } from 'rxjs';

import { storedLang } from './app-locale';

/** How long start-up waits for the reader's strings before it renders anyway. */
export const TRANSLATIONS_WAIT_MS = 30_000;

/**
 * QA D2351 (r63, slow 3G): the shell rendered before the strings arrived, so a
 * phone showed blank labels and buttons for seconds, then the English
 * fallback ("Sign In") before French. Start-up now waits for the reader's own
 * language (the boot screen in index.html is what shows meanwhile). A failed
 * or very slow download never blocks the application: it starts anyway.
 */
export function translationsReady(
  translate: TranslateService,
  waitMs = TRANSLATIONS_WAIT_MS,
): Promise<boolean> {
  const loaded: Observable<boolean> = translate.use(storedLang()).pipe(
    map(() => true),
    timeout(waitMs),
    catchError(() => of(false)),
  );
  return firstValueFrom(loaded, { defaultValue: false });
}
