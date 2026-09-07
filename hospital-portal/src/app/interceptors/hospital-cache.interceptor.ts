import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';
import { HospitalService } from '../services/hospital.service';

/**
 * Any write that can change which hospitals are active — create, update,
 * delete, suspend, restore, archive, purge, on a hospital or its whole
 * organisation, from whichever service issues it — forgets the scope
 * picker's cached first page, so a just-suspended tenant is never offered.
 */
const HOSPITAL_WRITE = /^\/?(super-admin\/)?(hospitals|organizations)(\/|$)/;

export const hospitalCacheInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method === 'GET' || !HOSPITAL_WRITE.test(req.url.replace(/^\/api\//, '/'))) {
    return next(req);
  }
  const hospitals = inject(HospitalService);
  return next(req).pipe(tap({ next: (event) => event.type !== 0 && hospitals.forgetFirstPage() }));
};
