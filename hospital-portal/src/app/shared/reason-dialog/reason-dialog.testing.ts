import { ComponentFixture } from '@angular/core/testing';

/**
 * QA D1201 (round 22): drive the shared reason dialog as a user does — the
 * specs of every screen that asks a reason through it use these.
 */
export function reasonDialog(fixture: ComponentFixture<unknown>): HTMLElement | null {
  fixture.detectChanges();
  return (fixture.nativeElement as HTMLElement).querySelector('[data-testid="reason-dialog"]');
}

/** Types `text` into the open reason dialog and clicks its confirm button. */
export function answerReasonDialog(fixture: ComponentFixture<unknown>, text: string): void {
  const dialog = reasonDialog(fixture);
  if (!dialog) throw new Error('no reason dialog is open');
  const field = dialog.querySelector<HTMLTextAreaElement>('[data-testid="reason-dialog-reason"]')!;
  field.value = text;
  field.dispatchEvent(new Event('input'));
  fixture.detectChanges();
  dialog.querySelector<HTMLButtonElement>('[data-testid="reason-dialog-confirm"]')!.click();
  fixture.detectChanges();
}
