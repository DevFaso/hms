/**
 * Offline-dispense interceptor (roadmap row 4 / T-68).
 *
 * Scope is intentionally narrow — this interceptor only touches
 * `POST /api/pharmacy/dispense`. Every other request flows through unchanged.
 * Two responsibilities:
 *
 * 1. Mint a stable {@code idempotencyKey} on the outbound DispenseRequest body
 *    if the caller did not supply one, so the eventual replay (whether from
 *    this same browser tab or a fresh tab after a crash) hits the V94
 *    UNIQUE-index dedup path on the backend.
 *
 * 2. Catch network failure (status 0 — offline or DNS) and 503 Service
 *    Unavailable (server temporarily down). When that happens, persist the
 *    request to the OfflineDispenseQueueService and return a synthetic
 *    HTTP 202 Accepted so the calling component can show a "Queued —
 *    will sync" badge instead of an error toast. Anything else (4xx auth
 *    failure, business-rule 400, server 500) is rethrown unchanged so it
 *    surfaces in the normal error path.
 *
 * The synthetic 202 carries a body shaped like a DispenseResponse with
 * status="QUEUED" so existing callers do not crash on a missing field. The
 * id field is the idempotency key — the real Dispense.id is unknown until
 * the replay succeeds.
 */
import {
  HttpEvent,
  HttpHandlerFn,
  HttpHeaders,
  HttpInterceptorFn,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, Observable, of, switchMap, throwError } from 'rxjs';

import { OfflineDispenseQueueService } from '../pharmacy/offline-dispense-queue.service';
import type { DispenseRequest, DispenseResponse } from '../services/pharmacy.service';

/** Path suffix that identifies the dispense create endpoint. */
const DISPENSE_PATH = '/pharmacy/dispense';

/**
 * Status codes that indicate "transient — try again later". Any other
 * error code means the server understood the request and rejected it for
 * a real reason; replaying that would not help.
 */
function isTransientFailure(status: number): boolean {
  return status === 0 || status === 503;
}

/**
 * True only for the dispense create endpoint — POST exactly to
 * .../pharmacy/dispense (no path suffix). The other endpoints under that
 * prefix (cancel, work-queue, by-prescription) are read-only or otherwise
 * not safe to silently retry.
 */
function isDispenseCreate(req: HttpRequest<unknown>): boolean {
  if (req.method !== 'POST') return false;
  const url = req.url.split('?')[0];
  return url.endsWith(DISPENSE_PATH);
}

/** Synthetic body returned when a request is queued instead of POSTed. */
function queuedResponseBody(idempotencyKey: string): Partial<DispenseResponse> {
  return {
    id: idempotencyKey,
    status: 'QUEUED',
  };
}

export const offlineDispenseInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> => {
  if (!isDispenseCreate(req)) {
    return next(req);
  }

  const queue = inject(OfflineDispenseQueueService);

  // Stamp idempotencyKey if the caller didn't. We mutate a clone of the
  // body, never the original — Angular's HttpRequest is intentionally
  // immutable and reusing references would surprise the caller.
  const original = (req.body ?? {}) as DispenseRequest;
  const stamped: DispenseRequest = {
    ...original,
    idempotencyKey: original.idempotencyKey ?? OfflineDispenseQueueService.mintId(original),
  };
  const stampedReq = req.clone({ body: stamped });

  return next(stampedReq).pipe(
    catchError((err: { status?: number }) => {
      const status = typeof err?.status === 'number' ? err.status : 500;
      if (!isTransientFailure(status)) {
        return throwError(() => err);
      }
      // Transient failure → enqueue + synth 202. The from() wrapper turns the
      // service's Promise<QueuedDispense> into an Observable so the
      // interceptor pipeline stays reactive end-to-end.
      return from(queue.enqueue(stamped)).pipe(
        switchMap((queued) =>
          of<HttpResponse<unknown>>(
            new HttpResponse({
              url: req.url,
              status: 202,
              statusText: 'Accepted (queued for offline replay)',
              headers: new HttpHeaders({
                // Surfaces the chosen key to the caller so it can correlate
                // the eventual "synced" event with the queued dispense.
                'X-Idempotency-Key': queued.id,
                'X-Offline-Queued': 'true',
              }),
              body: { data: queuedResponseBody(queued.id), success: true },
            }),
          ),
        ),
      );
    }),
  );
};
