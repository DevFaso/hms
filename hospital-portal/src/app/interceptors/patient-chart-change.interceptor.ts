import { HttpEventType, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject, filter, tap } from 'rxjs';

/**
 * QA D957: the patient banner still said "Aucune allergie connue" and "Aucun
 * problème actif" after the doctor added Pénicilline and B50.9 in the chart,
 * until a reload. Every summary that caches a patient's chart (the banner,
 * and any other reader) listens here, and any successful write under
 * /patients/{id}/… — whichever component or service sent it — says that
 * patient's chart changed.
 */
@Injectable({ providedIn: 'root' })
export class PatientChartChanges {
  private readonly changed = new Subject<string>();

  /** Called by the interceptor after a write on this patient succeeded. */
  notify(patientId: string): void {
    this.changed.next(patientId);
  }

  /** Emits each time a write on this patient's chart succeeds ('*': a write that names no patient). */
  of(patientId: string): Observable<string> {
    return this.changed.pipe(filter((id) => id === patientId || id === ANY_PATIENT));
  }
}

const PATIENT_WRITE = /^\/?patients\/([0-9a-fA-F-]{36})(?:\/([^?]*))?(?:\?|$)/;

/** Writes that change a visit (the banner's active encounter) without the patient in the URL. */
const VISIT_WRITE = /^\/?(encounters|reception)(\/|$)/;

/** Every subscriber is told: the write named no patient. */
export const ANY_PATIENT = '*';

/** Reads sent as a POST (the reason travels in the body): no chart changed. */
const READ_ONLY_POSTS =
  /(^|\/)(doctor-timeline|doctor-record|search|filter|lookup|query|preview|validate|check)$/;

export const patientChartChangeInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method === 'GET' || req.method === 'HEAD' || req.method === 'OPTIONS') {
    return next(req);
  }
  const path = req.url.replace(/^\/api\//, '/');
  const match = PATIENT_WRITE.exec(path);
  const visitWrite = !match && VISIT_WRITE.test(path) && !READ_ONLY_POSTS.test(path.split('?')[0]);
  if ((!match || READ_ONLY_POSTS.test(match[2] ?? '')) && !visitWrite) return next(req);
  const changes = inject(PatientChartChanges);
  const patientId = match ? match[1] : ANY_PATIENT;
  return next(req).pipe(
    tap((event) => {
      if (event.type === HttpEventType.Response && event.ok) changes.notify(patientId);
    }),
  );
};
