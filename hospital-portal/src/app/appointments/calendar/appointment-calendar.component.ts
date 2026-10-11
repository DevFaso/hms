import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  ViewEncapsulation,
  inject,
  signal,
} from '@angular/core';

import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription } from 'rxjs';

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
  FullCalendarModule,
} from '@fullcalendar/angular';
import dayGridPlugin from '@fullcalendar/angular/daygrid';
import timeGridPlugin from '@fullcalendar/angular/timegrid';
import interactionPlugin from '@fullcalendar/angular/interaction';
import classicThemePlugin from '@fullcalendar/angular/themes/classic';
// Imported from `fullcalendar` itself: the wrapper's own `locales/*`
// entrypoint is broken in 7.1.1 (its export map points at locales/*.mjs
// files the package does not ship). English is built in.
import esLocale from 'fullcalendar/locales/es';
import frLocale from 'fullcalendar/locales/fr';

import {
  AppointmentCalendarEvent,
  AppointmentCalendarService,
} from '../../services/appointment-calendar.service';
import { StaffService, StaffResponse } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { storedLang } from '../../shared/i18n/app-locale';

type State = 'idle' | 'loading' | 'ready' | 'error';

/**
 * Cadence-style visual scheduling grid for appointments. Wraps
 * FullCalendar with a free-tier feature set (timeGrid + dayGrid +
 * interaction) and adds a provider-filter dropdown so a single-
 * provider view substitutes for the Premium "resource-timeline" plan.
 *
 * <p>Click an event → navigate to {@code /appointments/:id}.
 * Click an empty slot → navigate to {@code /appointments/new} with
 * the date + start time pre-filled in the route's query string.
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
          (ngModelChange)="onProviderChange($event)"
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
        </p>
      }

      @if (hospitalId(); as hid) {
        <full-calendar [options]="calendarOptions" data-testid="appointment-calendar-grid" />
      }
    </section>
  `,
  // FullCalendar renders its DOM outside Angular's renderer, so emulated
  // encapsulation could never reach it; every rule in the stylesheet is
  // scoped under the page's .cal-page root instead.
  encapsulation: ViewEncapsulation.None,
  styleUrl: './appointment-calendar.component.scss',
})
export class AppointmentCalendarComponent implements OnInit, OnDestroy {
  protected readonly state = signal<State>('idle');
  protected readonly providers = signal<StaffResponse[]>([]);
  protected readonly hospitalId = signal<string | null>(null);
  protected selectedStaffId = '';

  private readonly calendarSvc = inject(AppointmentCalendarService);
  private readonly staffSvc = inject(StaffService);
  private readonly roleContext = inject(RoleContextService);
  private readonly router = inject(Router);

  private readonly destroyed$ = new Subject<void>();
  private inFlight?: Subscription;
  /** The range on screen, as last reported by datesSet. */
  private visibleRange?: { from: string; to: string };

  protected calendarOptions: CalendarOptions = {
    plugins: [classicThemePlugin, dayGridPlugin, timeGridPlugin, interactionPlugin],
    // Same source as LOCALE_ID: the language is fixed for the page's life
    // (switching it reloads), so reading it once here is enough.
    locales: [frLocale, esLocale],
    locale: storedLang(),
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
    dateClick: (arg: DateClickInfo) => this.openCreateForSlot(arg.date),
    datesSet: (arg) => {
      this.visibleRange = { from: toIsoDate(arg.start), to: toIsoDate(addDays(arg.end, -1)) };
      this.loadRange(this.visibleRange.from, this.visibleRange.to);
    },
    events: [],
  };

  ngOnInit(): void {
    const hid = this.roleContext.effectiveHospitalIdForRequest();
    this.hospitalId.set(hid ?? null);
    if (!hid) {
      this.state.set('error');
      return;
    }
    this.staffSvc.list(hid).subscribe({
      next: (s) => this.providers.set((s ?? []).filter((p) => isProvider(p))),
      error: () => this.providers.set([]),
    });
  }

  ngOnDestroy(): void {
    this.inFlight?.unsubscribe();
    this.destroyed$.next();
    this.destroyed$.complete();
  }

  protected onProviderChange(value: string): void {
    // datesSet fires only when the visible dates change, never for a new
    // filter, so reload the range on screen directly. Replacing `events`
    // (here and in loadRange) does not fire datesSet, so this cannot loop.
    this.selectedStaffId = value;
    this.calendarOptions = { ...this.calendarOptions, events: [] };
    if (this.visibleRange) {
      this.loadRange(this.visibleRange.from, this.visibleRange.to);
    }
  }

  private loadRange(from: string, to: string): void {
    const hid = this.hospitalId();
    if (!hid) return;
    this.inFlight?.unsubscribe();
    this.state.set('loading');
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
          this.calendarOptions = { ...this.calendarOptions, events };
          this.state.set('ready');
        },
        error: () => this.state.set('error'),
      });
  }

  private openAppointment(id: string): void {
    if (!id) return;
    this.router.navigate(['/appointments', id]);
  }

  private openCreateForSlot(date: Date): void {
    this.router.navigate(['/appointments/new'], {
      queryParams: {
        date: toIsoDate(date),
        time: toIsoTime(date),
      },
    });
  }
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
