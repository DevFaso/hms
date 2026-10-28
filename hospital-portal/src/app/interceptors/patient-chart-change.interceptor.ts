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

  /** Emits each time a write on this patient's chart succeeds. */
  of(patientId: string): Observable<string> {
    return this.changed.pipe(filter((id) => id === patientId));
  }
}

const PATIENT_WRITE = /^\/?patients\/([0-9a-fA-F-]{36})\/[^?]+/;

export const patientChartChangeInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method === 'GET' || req.method === 'HEAD' || req.method === 'OPTIONS') {
    return next(req);
  }
  const match = PATIENT_WRITE.exec(req.url.replace(/^\/api\//, '/'));
  if (!match) return next(req);
  const changes = inject(PatientChartChanges);
  const patientId = match[1];
  return next(req).pipe(
    tap((event) => {
      if (event.type === HttpEventType.Response && event.ok) changes.notify(patientId);
    }),
  );
};
