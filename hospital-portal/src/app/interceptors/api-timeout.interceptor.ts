import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { mergeMap, takeUntil, throwError, timer } from 'rxjs';
import { ApiTimeoutService } from '../core/api-timeout.service';

export const API_REQUEST_TIMEOUT_MS = 20_000;
export const API_UPLOAD_TIMEOUT_MS = 120_000;

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
  const deadline = req.reportProgress ? API_UPLOAD_TIMEOUT_MS : API_REQUEST_TIMEOUT_MS;
  return next(req).pipe(
    takeUntil(
      timer(deadline).pipe(
        mergeMap(() => {
          notice.noteTimeout(req.method);
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
