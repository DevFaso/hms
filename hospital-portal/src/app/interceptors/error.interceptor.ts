import {
  HttpInterceptorFn,
  HttpErrorResponse,
  HttpHandlerFn,
  HttpRequest,
  HttpEvent,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import {
  BehaviorSubject,
  EMPTY,
  Observable,
  catchError,
  filter,
  switchMap,
  take,
  throwError,
} from 'rxjs';
import { AuthService } from '../auth/auth.service';

const SILENT_403_PATTERNS = [
  /\/hospitals(\?|$|\/$)/,
  /\/hospitals\/[^/]+(\?|$)/,
  /\/departments(\?|$|\/)/,
  /\/organizations(\?|$|\/)/,
  /\/staff(\?|$|\/)/,
  /\/roles(\?|$|\/)/,
  /\/notifications(\?|$|\/)/,
];

const SILENT_401_PATTERNS = [/\/chat\/mark-read\//];

let isRefreshing = false;
const refreshDone$ = new BehaviorSubject<string | null>(null);

function cloneWithToken(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

function tryRefreshAndRetry(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  router: Router,
): Observable<HttpEvent<unknown>> {
  const isSilentBackground = SILENT_401_PATTERNS.some((p) => p.test(req.url));

  if (!isRefreshing) {
    isRefreshing = true;
    refreshDone$.next(null); // reset before the call

    return auth.refreshTokenRequest().pipe(
      switchMap((tokens) => {
        isRefreshing = false;
        auth.setToken(tokens.accessToken);
        if (tokens.refreshToken) {
          auth.setRefreshToken(tokens.refreshToken);
        }
        // Unblock all queued requests with the new token.
        refreshDone$.next(tokens.accessToken);

        return next(cloneWithToken(req, tokens.accessToken));
      }),
      catchError((refreshError) => {
        isRefreshing = false;
        refreshDone$.next('__REFRESH_FAILED__');
        // Reset to null so refreshDone$ is ready for the next login session.
        refreshDone$.next(null);
        // Refresh token is expired/invalid → full logout.
        auth.logout();
        void router.navigate(['/login']);
        return isSilentBackground ? EMPTY : throwError(() => refreshError);
      }),
    );
  }

  return refreshDone$.pipe(
    filter((t): t is string => t !== null),
    take(1),
    switchMap((newToken) => {
      if (newToken === '__REFRESH_FAILED__') {
        return throwError(
          () => new HttpErrorResponse({ status: 401, statusText: 'Refresh failed' }),
        );
      }
      return next(cloneWithToken(req, newToken));
    }),
  );
}

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        const isRefreshCall = req.url.includes('/auth/token/refresh');
        const isVerifyPassword = req.url.includes('/auth/verify-password');

        if (!isRefreshCall && !isVerifyPassword && auth.getRefreshToken()) {
          // We have a refresh token — attempt silent renewal and replay.
          return tryRefreshAndRetry(req, next, auth, router);
        }

        const isSilentBackground = SILENT_401_PATTERNS.some((p) => p.test(req.url));
        if (!isVerifyPassword && !isSilentBackground) {
          auth.logout();
          void router.navigate(['/login']);
        }
      } else if (error.status === 403) {
        const isSilent =
          (req.method === 'GET' || req.method === 'HEAD') &&
          SILENT_403_PATTERNS.some((pattern) => pattern.test(req.url));
        if (!isSilent) {
          void router.navigate(['/error/403']);
        }
      }
      return throwError(() => error);
    }),
  );
};
