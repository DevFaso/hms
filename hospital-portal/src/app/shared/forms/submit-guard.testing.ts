/**
 * QA D1124: a guarded submit button stays enabled, so `button.disabled` no
 * longer says whether the form may be sent. Specs ask this instead: true
 * while a request runs (`disabled`) or while `appSubmitGuard` would refuse the
 * click (`data-submit-blocked`). The click itself — refused, reason shown,
 * fields marked — is proven in submit-guard.directive.spec.ts.
 */
export function isSubmitBlocked(button: Element | null | undefined): boolean {
  if (!button) return false;
  return (
    (button as HTMLButtonElement).disabled === true ||
    button.getAttribute('data-submit-blocked') === 'true'
  );
}
