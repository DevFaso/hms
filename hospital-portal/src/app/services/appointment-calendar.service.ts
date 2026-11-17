import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Wire shape of {@code GET /api/appointments/calendar}. Field names match
 * the backend record so consumers can spread directly into a FullCalendar
 * EventInput. The auth interceptor prepends {@code /api}.
 */
export interface AppointmentCalendarEvent {
  id: string;
  patientId: string | null;
  patientName: string | null;
  resourceId: string | null;
  resourceName: string | null;
  title: string;
  start: string;
  end: string;
  status: string | null;
  reason: string | null;
}

@Injectable({ providedIn: 'root' })
export class AppointmentCalendarService {
  private readonly http = inject(HttpClient);
  private readonly url = '/appointments/calendar';

  /**
   * Hospital-scoped date-range slice. The (from, to) range may span at most
   * 42 days inclusive (the six-week month grid; the backend's
   * CALENDAR_MAX_RANGE_DAYS) — wider windows answer 400.
   */
  getRange(
    hospitalId: string,
    from: string,
    to: string,
    staffId?: string,
  ): Observable<AppointmentCalendarEvent[]> {
    let params = new HttpParams().set('hospitalId', hospitalId).set('from', from).set('to', to);
    if (staffId) params = params.set('staffId', staffId);
    return this.http.get<AppointmentCalendarEvent[]>(this.url, { params });
  }
}
