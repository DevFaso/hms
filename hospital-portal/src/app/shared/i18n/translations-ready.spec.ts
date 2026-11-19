import { TranslateService } from '@ngx-translate/core';
import { NEVER, of, throwError } from 'rxjs';

import { storedLang } from './app-locale';
import { translationsReady } from './translations-ready';

/**
 * QA D2351 (r63): a slow phone showed blank labels, then English, before
 * French. Start-up waits for the reader's strings, and never forever.
 */
describe('translationsReady (QA D2351)', () => {
  const service = (use: jasmine.Spy) => ({ use }) as unknown as TranslateService;

  it("waits for the reader's own language", async () => {
    const use = jasmine.createSpy('use').and.returnValue(of({}));
    await expectAsync(translationsReady(service(use))).toBeResolvedTo(true);
    expect(use).toHaveBeenCalledOnceWith(storedLang());
  });

  it('a failed download never blocks start-up', async () => {
    const use = jasmine.createSpy('use').and.returnValue(throwError(() => new Error('offline')));
    await expectAsync(translationsReady(service(use))).toBeResolvedTo(false);
  });

  it('a download that never ends never blocks start-up', async () => {
    const use = jasmine.createSpy('use').and.returnValue(NEVER);
    await expectAsync(translationsReady(service(use), 5)).toBeResolvedTo(false);
  });
});
