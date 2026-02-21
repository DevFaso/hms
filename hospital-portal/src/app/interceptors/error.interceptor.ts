import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // Don't auto-logout on failed lock-screen password verification
        const isVerifyPassword = req.url.includes('/auth/verify-password');
        if (!isVerifyPassword) {
          auth.logout();
          void router.navigate(['/login']);
        }
      } else if (error.status === 403) {
        void router.navigate(['/error/403']);
      }
      return throwError(() => error);
    }),
  );
};
