import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { mergeMap, takeUntil, tap, throwError, timer } from 'rxjs';
import { ApiTimeoutService } from '../core/api-timeout.service';

import {
  API_TIMEOUT_BUDGETS,
  API_TIMEOUT_PROFILE,
  withApiTimeoutProfile,
  isSignInRequest,
  timeoutNoticeMethod,
} from '../core/api-request-timeout';
export {
  API_REQUEST_TIMEOUT_MS,
  API_UPLOAD_TIMEOUT_MS,
  API_EXPORT_TIMEOUT_MS,
} from '../core/api-request-timeout';

/**
 * D3602: outermost, so CSRF bootstrap, refresh and requests waiting for a
 * refresh share a bounded deadline. Sent/progress events cannot cancel it.
 * Cancelling unsubscribes the XHR; a write may still have committed remotely.
 * Never replay a request here (in particular money or clinical writes).
 */
export const apiTimeoutInterceptor: HttpInterceptorFn = (req, next) => {
  // Local translation assets are not API calls. Let the existing startup
  // recovery handle slow assets; aborting these would remove error wording.
  if (/(?:^|\/)assets\/i18n\/(?:en|fr|es)\.json(?:[?#]|$)/i.test(req.url)) {
    return next(req);
  }

  const notice = inject(ApiTimeoutService);
  const translate = inject(TranslateService);
  const request = withApiTimeoutProfile(req);
  const deadline = API_TIMEOUT_BUDGETS[request.context.get(API_TIMEOUT_PROFILE)!];
  const sessionEpoch = notice.sessionEpoch;
  return next(request).pipe(
    tap((event) => {
      if (
        event instanceof HttpResponse &&
        isSignInRequest(request) &&
        sessionEpoch === notice.sessionEpoch
      )
        notice.resetForSession();
    }),
    takeUntil(
      timer(deadline).pipe(
        mergeMap(() => {
          const method = timeoutNoticeMethod(request);
          if (method) notice.noteTimeout(method, sessionEpoch);
          return throwError(
            () =>
              new HttpErrorResponse({
                status: 504,
                statusText: 'Request timeout',
                error: {
                  code: 'REQUEST_TIMEOUT',
                  message: translate.instant('API_TIMEOUT.MESSAGE'),
                },
              }),
          );
        }),
      ),
    ),
  );
};
