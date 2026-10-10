import { HttpContextToken, HttpErrorResponse } from '@angular/common/http';

/**
 * Marks a request that carries an MFA step-up (`X-Mfa-Token`): a destructive
 * super-admin action such as suspending or archiving a facility. Its refusal
 * (401 `mfa_required…` today, 403 with a reason code after #853) is the
 * dialog's to show: the error interceptor must neither refresh the session,
 * nor sign the user out, nor redirect to the forbidden page for it.
 */
export const STEP_UP_REQUEST = new HttpContextToken<boolean>(() => false);

/** The step-up header the hospital lifecycle endpoints read. */
export const MFA_TOKEN_HEADER = 'X-Mfa-Token';

/**
 * Is this the server refusing the step-up itself (a missing or wrong code),
 * rather than the session having expired? A 403 on a step-up request always
 * is; a 401 is when its body names the MFA requirement.
 */
export function isStepUpRefusal(error: HttpErrorResponse): boolean {
  if (error.status === 403) return true;
  if (error.status !== 401) return false;
  const body = error.error as { message?: unknown; code?: unknown; reason?: unknown } | null;
  const text = body && typeof body === 'object' ? [body.message, body.code, body.reason] : [body];
  return text.some((part) => typeof part === 'string' && /mfa/i.test(part));
}
