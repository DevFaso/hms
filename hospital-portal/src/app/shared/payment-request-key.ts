import { HttpErrorResponse } from '@angular/common/http';
import { MonoTypeOperatorFunction, retry, throwError, timer } from 'rxjs';
import { createBookingRequestId } from './booking-request-id';

/**
 * Billing plan PR 11 (QA D2430): a cash payment whose answer was lost on the way back was shown as
 * « Échec du paiement » although the server had saved it, and the cashier's second press recorded it
 * twice. Every money-taking dialog now generates ONE key when it opens and sends it with every press
 * (`clientRequestId`, `copayClientRequestId` at check-in). The server answers a key it already
 * recorded with that payment instead of writing a second one.
 */
export function newPaymentRequestKey(
  source: Crypto | undefined = globalThis.crypto,
): string | undefined {
  return createBookingRequestId(source);
}

/**
 * True when the server may have recorded the payment although no answer came back: no response at
 * all (status 0: network cut, aborted, timed out) or a gateway that gave up waiting (502, 504, and
 * 503 unless the server itself said it is read-only). A refusal (4xx) or a server error (500) is an
 * answer: nothing was saved.
 */
export function paymentOutcomeUnknown(err: unknown): boolean {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 503) return err.headers?.get('X-Readonly-Mode') !== 'true';
    return err.status === 0 || err.status === 502 || err.status === 504;
  }
  return (err as { name?: string } | null)?.name === 'TimeoutError';
}

/** The pause before the automatic retry of a payment whose answer was lost. */
export const PAYMENT_RETRY_DELAY_MS = 1500;

/**
 * Sends a keyed payment once more, with the same key, when its first answer was lost: the server
 * answers with the payment it may already hold, so the retry never takes the money twice. Without a
 * key (a browser with no crypto) nothing is retried: the call is not idempotent then.
 */
export function retryPaymentWithSameKey<T>(key: string | undefined): MonoTypeOperatorFunction<T> {
  return retry({
    count: key ? 1 : 0,
    delay: (err: unknown) =>
      paymentOutcomeUnknown(err) ? timer(PAYMENT_RETRY_DELAY_MS) : throwError(() => err),
  });
}

/** The i18n key of the warning shown when the outcome is still unknown after the retry. */
export const PAYMENT_OUTCOME_UNKNOWN_KEY = 'PAYMENT_OUTCOME.UNKNOWN';
