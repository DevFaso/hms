import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  ViewChild,
  ViewEncapsulation,
  inject,
  signal,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subscription } from 'rxjs';

// FullCalendar 7: the plugins are entrypoints of the Angular wrapper (the
// separate @fullcalendar/daygrid|timegrid|interaction packages ended at 6.x),
// the types come from the wrapper (core was renamed `fullcalendar`), the
// callback arg types were renamed *Arg -> *Info, and the look is a theme
// plugin plus its stylesheet (see appointment-calendar.component.scss).
import {
  CalendarOptions,
  DateClickInfo,
  EventClickInfo,
  EventInput,
  EventSourceFuncInfo,
  FullCalendarComponent,
  FullCalendarModule,
  LocaleInput,
} from '@fullcalendar/angular';
import dayGridPlugin from '@fullcalendar/angular/daygrid';
import timeGridPlugin from '@fullcalendar/angular/timegrid';
import interactionPlugin from '@fullcalendar/angular/interaction';
import classicThemePlugin from '@fullcalendar/angular/themes/classic';

import {
  AppointmentCalendarEvent,
  AppointmentCalendarService,
} from '../../services/appointment-calendar.service';
import { StaffService, StaffResponse } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { storedLang, SupportedLang } from '../../shared/i18n/app-locale';

type State = 'idle' | 'loading' | 'ready' | 'error';

/**
 * FullCalendar's locale for each portal language, loaded on demand so only
 * the one in use is downloaded. English is built in. Imported from
 * `fullcalendar` itself: the wrapper's `@fullcalendar/angular/locales/*`
 * export is broken in 7.1.1 (it maps to locales/*.mjs files the package
 * does not ship).
 */
const FULLCALENDAR_LOCALES: Partial<Record<SupportedLang, () => Promise<LocaleInput>>> = {
  fr: () => import('fullcalendar/locales/fr').then((m) => m.default),
  es: () => import('fullcalendar/locales/es').then((m) => m.default),
};

/**
 * Cadence-style visual scheduling grid for appointments. Wraps
 * FullCalendar with a free-tier feature set (timeGrid + dayGrid +
 * interaction) and adds a provider-filter dropdown so a single-
 * provider view substitutes for the Premium "resource-timeline" plan.
 *
 * <p>Click an event → navigate to {@code /appointments/:id}.
 * Click an empty slot → navigate to {@code /appointments/new} with the
 * date (and, for a timed slot, the start time) in the query string. The
 * form does not read them yet (tasklist.md, Standing platform debt).
 */
@Component({
  selector: 'app-appointment-calendar',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule, FullCalendarModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="cal-page" data-testid="appointment-calendar">
      <header class="cal-page__header">
        <div>
          <h1 class="page-title">{{ 'APPOINTMENT_CALENDAR.TITLE' | translate }}</h1>
          <p class="page-subtitle">{{ 'APPOINTMENT_CALENDAR.SUBTITLE' | translate }}</p>
        </div>
        <div class="cal-page__actions">
          <a class="btn-secondary" routerLink="/appointments">
            {{ 'APPOINTMENT_CALENDAR.BACK_TO_LIST' | translate }}
          </a>
        </div>
      </header>

      <div class="cal-page__filter">
        <label for="provider-filter">{{
          'APPOINTMENT_CALENDAR.FILTER_PROVIDER' | translate
        }}</label>
        <select
          id="provider-filter"
          [(ngModel)]="selectedStaffId"
          (ngModelChange)="onProviderChange()"
          data-testid="appointment-calendar-provider"
        >
          <option [ngValue]="''">{{ 'APPOINTMENT_CALENDAR.ALL_PROVIDERS' | translate }}</option>
          @for (s of providers(); track s) {
            <option [ngValue]="s.id">
              {{ s.name }}
            </option>
          }
        </select>
      </div>

      @if (state() === 'loading') {
        <p class="cal-page__loading" data-testid="appointment-calendar-loading">
          {{ 'APPOINTMENT_CALENDAR.LOADING' | translate }}
        </p>
      }

      @if (state() === 'error') {
        <p class="cal-page__error" data-testid="appointment-calendar-error">
          {{ 'APPOINTMENT_CALENDAR.ERROR' | translate }}
          @if (calendarOptions()) {
            <button
              type="button"
              class="btn-secondary"
              data-testid="appointment-calendar-retry"
              (click)="retry()"
            >
              {{ 'COMMON.RETRY' | translate }}
            </button>
          }
        </p>
      }

      @if (calendarOptions(); as opts) {
        <full-calendar [options]="opts" data-testid="appointment-calendar-grid" />
      }
    </section>
  `,
  // FullCalendar renders its DOM outside Angular's renderer, so emulated
  // encapsulation could never reach it: the page's own rules are scoped
  // under .cal-page, and the theme tokens sit on :root (see the stylesheet).
  encapsulation: ViewEncapsulation.None,
  styleUrl: './appointment-calendar.component.scss',
})
export class AppointmentCalendarComponent implements OnInit, OnDestroy {
  protected readonly state = signal<State>('idle');
  protected readonly providers = signal<StaffResponse[]>([]);
  protected selectedStaffId = '';

  private readonly calendarSvc = inject(AppointmentCalendarService);
  private readonly staffSvc = inject(StaffService);
  private readonly roleContext = inject(RoleContextService);
  private readonly router = inject(Router);

  private inFlight?: Subscription;

  @ViewChild(FullCalendarComponent) private calendar?: FullCalendarComponent;

  /** Null until the locale is in: the calendar never renders in English first. */
  protected readonly calendarOptions = signal<CalendarOptions | null>(null);

  ngOnInit(): void {
    const hid = this.roleContext.effectiveHospitalIdForRequest();
    if (!hid) {
      this.state.set('error');
      return;
    }
    this.staffSvc.list(hid).subscribe({
      next: (s) => this.providers.set((s ?? []).filter((p) => isProvider(p))),
      error: () => this.providers.set([]),
    });
    // Same source as LOCALE_ID: the language is fixed for the page's life
    // (switching it reloads). A locale chunk that fails to load leaves the
    // calendar in English rather than not there at all.
    const loadLocale = FULLCALENDAR_LOCALES[storedLang()];
    const locale: Promise<LocaleInput | null> = loadLocale
      ? loadLocale().catch(() => null)
      : Promise.resolve(null);
    void locale.then((l) => this.calendarOptions.set(this.buildOptions(hid, l)));
  }

  ngOnDestroy(): void {
    this.inFlight?.unsubscribe();
  }

  protected onProviderChange(): void {
    // [(ngModel)] has already stored the pick. Empty the grid first so the
    // previous provider's patients are never shown under the new filter,
    // even if the refetch fails; then fetch the unchanged range again.
    const api = this.calendar?.getApi();
    api?.removeAllEvents();
    api?.refetchEvents();
  }

  protected retry(): void {
    this.calendar?.getApi().refetchEvents();
  }

  private buildOptions(hid: string, locale: LocaleInput | null): CalendarOptions {
    return {
      plugins: [classicThemePlugin, dayGridPlugin, timeGridPlugin, interactionPlugin],
      locale: locale ?? 'en',
      initialView: 'timeGridWeek',
      headerToolbar: {
        left: 'prev,next today',
        center: 'title',
        right: 'dayGridMonth,timeGridWeek,timeGridDay',
      },
      weekends: true,
      nowIndicator: true,
      height: 'auto',
      allDaySlot: false,
      slotMinTime: '06:00:00',
      slotMaxTime: '21:00:00',
      eventClick: (arg: EventClickInfo) => this.openAppointment(arg.event.id),
      dateClick: (arg: DateClickInfo) => this.openCreateForSlot(arg.date, arg.allDay),
      // A function source: FullCalendar calls it for every range it needs
      // (first render, navigation, view change) and on refetchEvents(), so
      // the provider filter and Retry go through the same single path.
      events: (info, success, failure) => this.fetchEvents(hid, info, success, failure),
      // Lazy fetching would reuse a range already asked for, and FullCalendar
      // counts a FAILED fetch as asked for: Week -> Day after an error would
      // never retry. Every view change fetches instead (fresh data, too).
      lazyFetching: false,
      // Every failed fetch lands here (handling it also stops FullCalendar
      // logging "Unhandled event source error"). The grid is emptied: after
      // a failure nothing on screen can be trusted to match the filter.
      eventSourceFailure: () => {
        this.state.set('error');
        this.calendar?.getApi().removeAllEvents();
      },
    };
  }

  private fetchEvents(
    hid: string,
    info: EventSourceFuncInfo,
    success: (events: EventInput[]) => void,
    failure: (error: Error) => void,
  ): void {
    // A newer fetch supersedes an older one (FullCalendar ignores the stale
    // answer anyway); dropping the subscription also stops its side effects.
    this.inFlight?.unsubscribe();
    this.state.set('loading');
    const from = toIsoDate(info.start);
    const to = toIsoDate(addDays(info.end, -1)); // FullCalendar's end is exclusive
    this.inFlight = this.calendarSvc
      .getRange(hid, from, to, this.selectedStaffId || undefined)
      .subscribe({
        next: (rows) => {
          const events: EventInput[] = rows.map((r: AppointmentCalendarEvent) => ({
            id: r.id,
            title: r.title,
            start: r.start,
            end: r.end,
            extendedProps: {
              status: r.status,
              reason: r.reason,
              patientId: r.patientId,
              resourceId: r.resourceId,
              resourceName: r.resourceName,
            },
          }));
          this.state.set('ready');
          success(events);
        },
        error: (err: unknown) => failure(toError(err)),
      });
  }

  private openAppointment(id: string): void {
    if (!id) return;
    this.router.navigate(['/appointments', id]);
  }

  private openCreateForSlot(date: Date, allDay: boolean): void {
    // A month-view day cell is all-day: its Date is midnight, which is not a
    // time anyone picked, so only the date goes along.
    const queryParams: Record<string, string> = { date: toIsoDate(date) };
    if (!allDay) queryParams['time'] = toIsoTime(date);
    this.router.navigate(['/appointments/new'], { queryParams });
  }
}

/**
 * An Error for FullCalendar's failure callback that keeps what went wrong.
 * An HttpErrorResponse is not an Error, and String() of it is
 * "[object Object]"; its message (or status) is, and the original stays as
 * the cause.
 */
function toError(err: unknown): Error {
  if (err instanceof Error) return err;
  const { message, status } = (err ?? {}) as { message?: unknown; status?: unknown };
  let text = String(err);
  if (typeof message === 'string' && message) text = message;
  else if (typeof status === 'number') text = `HTTP ${status}`;
  return new Error(text, { cause: err });
}

function isProvider(s: StaffResponse): boolean {
  // Best-effort role filter — staff with an explicit role flag for the
  // common ordering roles. Falls back to surfacing everyone if no role
  // info is available so admins can pick from the full list.
  const role =
    (s as unknown as { roleCode?: string; jobTitle?: string }).roleCode ??
    (s as unknown as { jobTitle?: string }).jobTitle ??
    '';
  if (!role) return true;
  const r = role.toUpperCase();
  return (
    r.includes('DOCTOR') ||
    r.includes('NURSE') ||
    r.includes('MIDWIFE') ||
    r.includes('SURGEON') ||
    r.includes('PROVIDER')
  );
}

function toIsoDate(d: Date): string {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

function toIsoTime(d: Date): string {
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

function addDays(d: Date, n: number): Date {
  const out = new Date(d);
  out.setDate(out.getDate() + n);
  return out;
}
