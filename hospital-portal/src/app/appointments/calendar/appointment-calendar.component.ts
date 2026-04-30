import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, Subscription } from 'rxjs';

import { CalendarOptions, EventClickArg, EventInput } from '@fullcalendar/core';
import { FullCalendarModule } from '@fullcalendar/angular';
import dayGridPlugin from '@fullcalendar/daygrid';
import timeGridPlugin from '@fullcalendar/timegrid';
import interactionPlugin, { DateClickArg } from '@fullcalendar/interaction';

import {
  AppointmentCalendarEvent,
  AppointmentCalendarService,
} from '../../services/appointment-calendar.service';
import { StaffService, StaffResponse } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';

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
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, FullCalendarModule],
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
          <option *ngFor="let s of providers()" [ngValue]="s.id">
            {{ s.name }}
          </option>
        </select>
      </div>

      <p
        *ngIf="state() === 'loading'"
        class="cal-page__loading"
        data-testid="appointment-calendar-loading"
      >
        {{ 'APPOINTMENT_CALENDAR.LOADING' | translate }}
      </p>

      <p
        *ngIf="state() === 'error'"
        class="cal-page__error"
        data-testid="appointment-calendar-error"
      >
        {{ 'APPOINTMENT_CALENDAR.ERROR' | translate }}
      </p>

      <full-calendar
        *ngIf="hospitalId() as hid"
        [options]="calendarOptions"
        data-testid="appointment-calendar-grid"
      />
    </section>
  `,
  styles: [
    `
      .cal-page {
        padding: 1.5rem;
      }
      .cal-page__header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: 1rem;
        gap: 1rem;
      }
      .cal-page__filter {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        margin-bottom: 1rem;

        label {
          font-size: 0.8125rem;
          color: #475569;
        }
        select {
          padding: 0.4rem 0.6rem;
          border: 1px solid #d1d5db;
          border-radius: 6px;
        }
      }
      .cal-page__error {
        color: var(--danger, #b00020);
      }
    `,
  ],
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

  protected calendarOptions: CalendarOptions = {
    plugins: [dayGridPlugin, timeGridPlugin, interactionPlugin],
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
    eventClick: (arg: EventClickArg) => this.openAppointment(arg.event.id),
    dateClick: (arg: DateClickArg) => this.openCreateForSlot(arg.date),
    datesSet: (arg) => this.loadRange(toIsoDate(arg.start), toIsoDate(addDays(arg.end, -1))),
    events: [],
  };

  ngOnInit(): void {
    const hid = this.roleContext.activeHospitalId;
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

  protected onProviderChange(_value: string): void {
    // The next datesSet (FullCalendar refreshes the visible range on
    // re-render) will trigger a fresh loadRange with the new staff filter.
    // Force the calendar to refetch its events by replacing the array.
    this.calendarOptions = { ...this.calendarOptions, events: [] };
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
