import {
  HttpContext,
  HttpContextToken,
  HttpErrorResponse,
  HttpHeaders,
} from '@angular/common/http';

/**
 * Marks a request that carries an MFA step-up (`X-Mfa-Token`): a destructive
 * super-admin action such as suspending or archiving a facility. Its
 * step-up refusal is the dialog's to show: the error interceptor must neither
 * refresh the session, nor sign the user out, nor redirect to the forbidden
 * page for it. Any other refusal of a marked request goes the normal way.
 */
export const STEP_UP_REQUEST = new HttpContextToken<boolean>(() => false);

/** The step-up header every step-up endpoint reads. */
export const MFA_TOKEN_HEADER = 'X-Mfa-Token';

/**
 * The options of EVERY request that sends a step-up: the code in
 * `X-Mfa-Token` (when given) and the {@link STEP_UP_REQUEST} mark, so a
 * refused code comes back to the dialog instead of refreshing the session or
 * signing the user out. A service that sends `X-Mfa-Token` builds its options
 * here, never by hand, so it cannot forget the mark.
 */
export function stepUpRequestOptions(mfaToken?: string | null): {
  headers: HttpHeaders;
  context: HttpContext;
} {
  return {
    headers: new HttpHeaders(mfaToken ? { [MFA_TOKEN_HEADER]: mfaToken } : {}),
    context: new HttpContext().set(STEP_UP_REQUEST, true),
  };
}

/**
 * The hospital lifecycle actions the backend guards with an MFA step-up
 * (`requireStepUp` in HospitalLifecycleServiceImpl): the single list the
 * lifecycle dialogs and HospitalLifecycleService read.
 */
export const STEP_UP_ACTIONS: ReadonlySet<string> = new Set([
  'suspend',
  'archive',
  'schedule-purge',
]);

/** Does this lifecycle action send an MFA step-up? */
export function isStepUpAction(action: string): boolean {
  return STEP_UP_ACTIONS.has(action);
}

/**
 * The translated message for a step-up refusal: a wrong or missing code is
 * not the same remedy as "enrol MFA first" or "try again in a moment".
 */
export function stepUpRefusalKey(error: HttpErrorResponse): string {
  const body = error.error as { message?: unknown; code?: unknown; reason?: unknown } | null;
  const text = [body?.reason, body?.code, body?.message]
    .filter((part): part is string => typeof part === 'string')
    .join(' ')
    .toLowerCase();
  if (text.includes('notenrolled') || text.includes('must enrol')) {
    return 'HOSPITAL_LIFECYCLE.ERROR.STEP_UP_NOT_ENROLLED';
  }
  if (text.includes('unavailable')) {
    return 'HOSPITAL_LIFECYCLE.ERROR.STEP_UP_UNAVAILABLE';
  }
  return 'HOSPITAL_LIFECYCLE.ERROR.STEP_UP_FAILED';
}

/** The reason-code prefix of a step-up refusal answered 403 (hotfix #853). */
const STEP_UP_REASON_PREFIX = 'mfa.stepup.';

/**
 * Is this the server refusing the step-up itself (a missing or wrong code),
 * rather than the session having expired or the action being forbidden?
 * Only:
 * - a 401 whose body names MFA (`mfa_required: …`, today's answer), or
 * - a 403 whose reason code starts with `mfa.stepup.` (#853's answer).
 *
 * Any other 403 (a hospital-scope refusal, a role refusal) is NOT one: it
 * goes through the interceptor's normal handling and reads as "not allowed".
 */
export function isStepUpRefusal(error: HttpErrorResponse): boolean {
  const body = error.error as { message?: unknown; code?: unknown; reason?: unknown } | null;
  const fields = body && typeof body === 'object' ? [body.message, body.code, body.reason] : [body];
  if (error.status === 401) {
    return fields.some((part) => typeof part === 'string' && /mfa/i.test(part));
  }
  if (error.status === 403 && body && typeof body === 'object') {
    return [body.reason, body.code].some(
      (part) => typeof part === 'string' && part.startsWith(STEP_UP_REASON_PREFIX),
    );
  }
  return false;
}
