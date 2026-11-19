import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';

import { ConnectivityService } from '../core/connectivity.service';

/**
 * QA D2352: every request tells the connectivity banner whether it reached
 * the server. Innermost in the chain, so it sees the network's own answer: a
 * dispense queued offline (a synthetic 202 from offlineDispenseInterceptor)
 * is never mistaken for the connection being back.
 */
export const connectivityInterceptor: HttpInterceptorFn = (req, next) => {
  const connectivity = inject(ConnectivityService);
  return next(req).pipe(
    tap({
      next: (event) => {
        if (event instanceof HttpResponse) connectivity.noteResponse();
      },
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 0) {
          connectivity.noteNetworkFailure();
        } else if (error instanceof HttpErrorResponse) {
          connectivity.noteResponse();
        }
      },
    }),
  );
};
