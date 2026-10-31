import { ComponentFixture } from '@angular/core/testing';

/**
 * QA D1004: the keyboard contract of a hand-built modal, driven the way a
 * keyboard user meets it. Focus the button that opens the dialog and press it;
 * focus must then be INSIDE a named modal dialog; Escape (sent to whatever has
 * focus, as a real key press is) must close it, and focus must come back to
 * the opener.
 *
 * The fixture must be attached to the document (`document.body.appendChild`)
 * for focus to move at all.
 */
export function expectKeyboardModal(fixture: ComponentFixture<unknown>, opener: HTMLElement): void {
  const host = fixture.nativeElement as HTMLElement;
  expect(host.isConnected).withContext('attach the fixture to the document').toBeTrue();
  opener.focus();
  opener.click();
  fixture.detectChanges();

  const active = document.activeElement as HTMLElement | null;
  const dialog = active?.closest<HTMLElement>('[role="dialog"]') ?? null;
  expect(dialog).withContext('focus moved into a dialog when it opened').not.toBeNull();
  if (!dialog) return;
  expect(host.contains(dialog)).toBeTrue();
  expect(dialog.getAttribute('aria-modal')).toBe('true');
  // Named by its title (aria-labelledby) or, for a menu with no title, an aria-label.
  const titleId = dialog.getAttribute('aria-labelledby') ?? '';
  const name =
    titleId === ''
      ? (dialog.getAttribute('aria-label') ?? '')
      : (document.getElementById(titleId)?.textContent ?? '');
  expect(name.trim()).withContext('the dialog has a name').not.toBe('');

  active!.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
  );
  fixture.detectChanges();
  expect(dialog.isConnected).withContext('Escape closed the dialog').toBeFalse();
  expect(document.activeElement).withContext('focus went back to the opener').toBe(opener);
}
