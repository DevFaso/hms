import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { fakeAsync, tick } from '@angular/core/testing';
import { defer, Observable, of, throwError } from 'rxjs';
import {
  newPaymentRequestKey,
  PAYMENT_RETRY_DELAY_MS,
  paymentOutcomeUnknown,
  retryPaymentWithSameKey,
} from './payment-request-key';

/** Billing plan PR 11 (QA D2430): one key per dialog, and no « Échec » when the server may have saved it. */
describe('payment-request-key', () => {
  const lost = new HttpErrorResponse({ status: 0, statusText: 'Unknown Error' });

  it('makes a v4 UUID, a new one each time', () => {
    const a = newPaymentRequestKey();
    const b = newPaymentRequestKey();
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(a).not.toEqual(b);
  });

  it('reads no answer and a gateway time-out as "may have been saved", a refusal as an answer', () => {
    expect(paymentOutcomeUnknown(lost)).toBeTrue();
    expect(paymentOutcomeUnknown(new HttpErrorResponse({ status: 504 }))).toBeTrue();
    expect(paymentOutcomeUnknown(new HttpErrorResponse({ status: 502 }))).toBeTrue();
    expect(paymentOutcomeUnknown(new HttpErrorResponse({ status: 503 }))).toBeTrue();
    expect(paymentOutcomeUnknown({ name: 'TimeoutError' })).toBeTrue();
    expect(paymentOutcomeUnknown(new HttpErrorResponse({ status: 400 }))).toBeFalse();
    expect(paymentOutcomeUnknown(new HttpErrorResponse({ status: 409 }))).toBeFalse();
    expect(paymentOutcomeUnknown(new HttpErrorResponse({ status: 500 }))).toBeFalse();
    const readOnly = new HttpErrorResponse({
      status: 503,
      headers: new HttpHeaders({ 'X-Readonly-Mode': 'true' }),
    });
    expect(paymentOutcomeUnknown(readOnly)).toBeFalse();
  });

  /** A call that fails with `first`, then answers with `then`. Counts the sends. */
  function flaky(first: unknown, sends: { n: number }): Observable<string> {
    return defer(() => {
      sends.n++;
      return sends.n === 1 ? throwError(() => first) : of('recorded');
    });
  }

  it('sends a keyed payment once more, with the same key, when the answer was lost', fakeAsync(() => {
    const sends = { n: 0 };
    let result: string | undefined;
    flaky(lost, sends)
      .pipe(retryPaymentWithSameKey<string>('key-1'))
      .subscribe((r) => (result = r));
    tick(PAYMENT_RETRY_DELAY_MS);
    expect(sends.n).toBe(2);
    expect(result).toBe('recorded');
  }));

  it('never resends a refusal, nor a payment without a key', fakeAsync(() => {
    const refused = { n: 0 };
    let refusal: unknown;
    flaky(new HttpErrorResponse({ status: 400 }), refused)
      .pipe(retryPaymentWithSameKey<string>('key-1'))
      .subscribe({ error: (e) => (refusal = e) });
    tick(PAYMENT_RETRY_DELAY_MS);
    expect(refused.n).toBe(1);
    expect((refusal as HttpErrorResponse).status).toBe(400);

    const unkeyed = { n: 0 };
    let lostError: unknown;
    flaky(lost, unkeyed)
      .pipe(retryPaymentWithSameKey<string>(undefined))
      .subscribe({ error: (e) => (lostError = e) });
    tick(PAYMENT_RETRY_DELAY_MS);
    expect(unkeyed.n).toBe(1);
    expect(paymentOutcomeUnknown(lostError)).toBeTrue();
  }));
});
