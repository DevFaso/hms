import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { ConfirmService } from './confirm.service';

/**
 * QA D1229 (round 24): the in-app confirmation that replaces the browser's
 * window.confirm everywhere in the portal.
 */
describe('ConfirmService', () => {
  let service: ConfirmService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TranslateModule.forRoot()] });
    service = TestBed.inject(ConfirmService);
  });

  afterEach(() => {
    document.querySelectorAll('[data-testid="confirm-dialog"]').forEach((d) => d.remove());
  });

  const dialog = (): HTMLElement | null =>
    document.querySelector<HTMLElement>('[data-testid="confirm-dialog"]');

  it('opens the shared dialog with the question and resolves true on confirm', async () => {
    const answer = service.ask({
      titleKey: 'PORTAL.DOCUMENTS.DELETE_CONFIRM',
      confirmKey: 'COMMON.DELETE',
    });
    expect(dialog()).not.toBeNull();
    expect(dialog()?.getAttribute('role')).toBe('dialog');
    expect(dialog()?.textContent).toContain('PORTAL.DOCUMENTS.DELETE_CONFIRM');
    expect(dialog()?.textContent).toContain('COMMON.DELETE');
    document.querySelector<HTMLButtonElement>('[data-testid="confirm-dialog-confirm"]')!.click();
    expect(await answer).toBeTrue();
    expect(dialog()).toBeNull();
  });

  it('resolves false on Cancel and on Escape, and removes the dialog', async () => {
    const cancelled = service.ask({ titleKey: 'X' });
    document.querySelector<HTMLButtonElement>('[data-testid="confirm-dialog-cancel"]')!.click();
    expect(await cancelled).toBeFalse();
    expect(dialog()).toBeNull();

    const escaped = service.ask({ titleKey: 'X' });
    dialog()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(await escaped).toBeFalse();
    expect(dialog()).toBeNull();
  });

  it('shows a server sentence before the question, and asks one question at a time', async () => {
    const first = service.ask({
      titleKey: 'BILLING.SUPPLEMENTARY_CONFIRM',
      message: 'Already invoiced RHA-12',
    });
    expect(dialog()?.textContent).toContain('Already invoiced RHA-12');
    expect(await service.ask({ titleKey: 'Y' })).toBeFalse();
    document.querySelector<HTMLButtonElement>('[data-testid="confirm-dialog-cancel"]')!.click();
    expect(await first).toBeFalse();
  });
});
