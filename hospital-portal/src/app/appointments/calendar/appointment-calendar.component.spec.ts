import { computed } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';
import type { CalendarApi, FullCalendarComponent } from '@fullcalendar/angular';

import { AppointmentCalendarComponent } from './appointment-calendar.component';
import {
  AppointmentCalendarEvent,
  AppointmentCalendarService,
} from '../../services/appointment-calendar.service';
import { StaffService } from '../../services/staff.service';
import { RoleContextService } from '../../core/role-context.service';
import { resetStoredLangForTests } from '../../shared/i18n/app-locale';

describe('AppointmentCalendarComponent', () => {
  let fixture: ComponentFixture<AppointmentCalendarComponent>;
  let calendarSvc: jasmine.SpyObj<AppointmentCalendarService>;
  let staffSvc: jasmine.SpyObj<StaffService>;

  // Today at 09:00, so the event falls in the week the calendar opens on.
  const nine = new Date();
  nine.setHours(9, 0, 0, 0);
  const local = (d: Date): string => {
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:00`;
  };
  const sample: AppointmentCalendarEvent = {
    id: 'a-1',
    patientId: 'p-1',
    patientName: 'Alice Patient',
    resourceId: 's-1',
    resourceName: 'Dr Provider',
    title: 'Alice Patient',
    start: local(nine),
    end: local(new Date(nine.getTime() + 30 * 60_000)),
    status: 'SCHEDULED',
    reason: 'Follow-up',
  };

  async function configure(activeHospital: string | null = 'h1'): Promise<void> {
    calendarSvc = jasmine.createSpyObj<AppointmentCalendarService>('AppointmentCalendarService', [
      'getRange',
    ]);
    calendarSvc.getRange.and.returnValue(of([sample]));
    staffSvc = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffSvc.list.and.returnValue(of([]));
    const role = jasmine.createSpyObj<RoleContextService>('RoleContextService', [], {
      effectiveHospitalIdForRequest: computed(() => activeHospital),
    });

    await TestBed.configureTestingModule({
      imports: [AppointmentCalendarComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: AppointmentCalendarService, useValue: calendarSvc },
        { provide: StaffService, useValue: staffSvc },
        { provide: RoleContextService, useValue: role },
      ],
    }).compileComponents();
  }

  interface CalendarHooks {
    eventClick?: (a: { event: { id: string } }) => void;
    dateClick?: (a: { date: Date; allDay: boolean }) => void;
    events?: (
      info: { start: Date; end: Date },
      success: (e: unknown[]) => void,
      failure: (e: Error) => void,
    ) => void;
  }

  function options(): CalendarHooks | null {
    return (
      fixture.componentInstance as unknown as { calendarOptions: () => CalendarHooks | null }
    ).calendarOptions();
  }

  function api(): CalendarApi {
    return (
      fixture.componentInstance as unknown as { calendar: FullCalendarComponent }
    ).calendar.getApi();
  }

  function grid(): HTMLElement | null {
    return fixture.nativeElement.querySelector('[data-testid="appointment-calendar-grid"]');
  }

  function errorBanner(): HTMLElement | null {
    return fixture.nativeElement.querySelector('[data-testid="appointment-calendar-error"]');
  }

  /** Waits for the locale to load and the calendar to render (its first fetch runs then). */
  async function calendarReady(): Promise<void> {
    for (let i = 0; i < 200 && !options(); i++) {
      await new Promise((r) => setTimeout(r, 10));
    }
    fixture.detectChanges();
  }

  /** configure → create → first render; returns the FullCalendar host. */
  async function render(activeHospital: string | null = 'h1'): Promise<HTMLElement> {
    await configure(activeHospital);
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();
    if (activeHospital) await calendarReady();
    return grid() as HTMLElement;
  }

  /** What a colour value resolves to, measured from a probe in <body>. */
  function resolve(value: string): string {
    const probe = document.createElement('span');
    probe.style.backgroundColor = value;
    document.body.appendChild(probe);
    const color = getComputedStyle(probe).backgroundColor;
    probe.remove();
    return color;
  }

  function pickProvider(id: string, name: string): void {
    (
      fixture.componentInstance as unknown as { providers: { set(v: unknown[]): void } }
    ).providers.set([{ id, name }]);
    fixture.detectChanges();
    const select: HTMLSelectElement = fixture.nativeElement.querySelector(
      '[data-testid="appointment-calendar-provider"]',
    );
    select.value = select.options[1].value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('renders the calendar shell when a hospital is active', async () => {
    await render('h1');

    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar"]'),
    ).not.toBeNull();
    expect(staffSvc.list).toHaveBeenCalledWith('h1');
  });

  it('renders FullCalendar 7 with the classic theme mapped onto the portal tokens', async () => {
    // FullCalendar 7 ships no CSS of its own: without the skeleton + theme
    // stylesheets and the theme plugin, the grid renders as unstyled markup.
    // This pins all three, and that the theme colours come from the design
    // tokens rather than FullCalendar's stock blue palette.
    const host = await render('h1');
    const buttons = Array.from(host.querySelectorAll('button'));
    expect(buttons.length).toBeGreaterThan(0);

    const primary = resolve('var(--primary)');
    expect(primary).not.toBe('rgba(0, 0, 0, 0)');

    const backgrounds = buttons.map((b) => getComputedStyle(b).backgroundColor);
    expect(backgrounds).toContain(primary);

    // Text on primary is plain white, not tied to a token the portal does
    // not have: defining a --surface page colour later must not recolour it.
    document.documentElement.style.setProperty('--surface', 'rgb(1, 2, 3)');
    try {
      expect(resolve('var(--fc-classic-button-foreground)')).toBe(resolve('#fff'));
    } finally {
      document.documentElement.style.removeProperty('--surface');
    }

    // The probe sits directly in <body>, where FullCalendar portals its
    // "+N more" popover: it only sees the tokens if they are not page-scoped.
    expect(resolve('var(--fc-classic-button)')).toBe(primary);

    // The keyboard focus ring is the 5.10:1 primary, not the fills-only light.
    expect(buttons.map((b) => getComputedStyle(b).outlineColor)).toContain(primary);

    // The selection highlight is painted over the grid, so it must be translucent.
    const highlight = resolve('var(--fc-classic-highlight)');
    expect(highlight).toMatch(/(\/\s*0?\.\d+\)|,\s*0?\.\d+\))$/);

    // The provider <select> boundary reaches 3:1: it uses --text-secondary.
    const select: HTMLSelectElement = fixture.nativeElement.querySelector(
      '[data-testid="appointment-calendar-provider"]',
    );
    expect(getComputedStyle(select).borderTopColor).toBe(resolve('var(--text-secondary)'));
  });

  it('marks the active view tab by more than a shade of the same fill', async () => {
    // The theme tells the selected view apart only by --primary-dark against
    // --primary (about 1.5:1); WCAG 1.4.11 wants 3:1 for a state indicator.
    const host = await render('h1');
    const tabs = Array.from(host.querySelectorAll<HTMLElement>('[role="tab"]'));
    const selected = tabs.filter((t) => t.getAttribute('aria-selected') === 'true');
    const others = tabs.filter((t) => t.getAttribute('aria-selected') !== 'true');
    expect(selected.length).toBe(1);
    expect(others.length).toBe(2);

    const primary = resolve('var(--primary)');
    expect(getComputedStyle(selected[0]).backgroundColor).toBe(resolve('#fff'));
    expect(getComputedStyle(selected[0]).color).toBe(primary);
    for (const t of others) {
      expect(getComputedStyle(t).backgroundColor).toBe(primary);
    }
  });

  describe('renders in the portal language', () => {
    let savedLang: string | null;
    beforeEach(() => {
      savedLang = localStorage.getItem('lang');
    });
    afterEach(() => {
      if (savedLang === null) localStorage.removeItem('lang');
      else localStorage.setItem('lang', savedLang);
      resetStoredLangForTests();
    });

    async function toolbarText(lang: string): Promise<string[]> {
      localStorage.setItem('lang', lang);
      resetStoredLangForTests();
      await configure('h1');
      fixture = TestBed.createComponent(AppointmentCalendarComponent);
      fixture.detectChanges();
      // Nothing renders until the locale is in, so there is no English
      // first paint that flips to the portal language afterwards.
      expect(grid()).withContext('rendered before its locale loaded').toBeNull();
      await calendarReady();
      // Visible text and accessible names (prev/next are icon-only).
      return Array.from((grid() as HTMLElement).querySelectorAll('button')).flatMap((b) => [
        (b.textContent ?? '').trim(),
        b.getAttribute('aria-label') ?? '',
      ]);
    }

    it('French', async () => {
      const text = await toolbarText('fr');
      expect(text).toContain("Aujourd'hui");
      expect(text).toContain('Mois');
      expect(text).toContain('Précédent');
    });

    it('English (built in; must not fall back to another locale)', async () => {
      const text = await toolbarText('en');
      expect(text).toContain('Today');
      expect(text).toContain('Month');
    });

    it('Spanish', async () => {
      const text = await toolbarText('es');
      expect(text).toContain('Hoy');
      expect(text).toContain('Mes');
      expect(text).toContain('Semana siguiente');
    });
  });

  it('shows the error state when no hospital is active', async () => {
    await render(null);

    expect(errorBanner()).not.toBeNull();
    expect(grid()).toBeNull();
  });

  it('refetches the visible range for the new provider when the filter changes', async () => {
    // The range on screen does not change, so FullCalendar would never fetch
    // on its own: the filter must ask it to.
    await render('h1');
    // The real calendar fetched its range exactly once on render.
    expect(calendarSvc.getRange).toHaveBeenCalledTimes(1);
    const [hid, from, to, staff] = calendarSvc.getRange.calls.mostRecent().args;
    expect(hid).toBe('h1');
    expect(staff).toBeUndefined();

    pickProvider('s-9', 'Dr Nine');

    expect(calendarSvc.getRange).toHaveBeenCalledTimes(2);
    expect(calendarSvc.getRange.calls.mostRecent().args).toEqual(['h1', from, to, 's-9']);
  });

  it("clears the previous provider's patients at once on a filter change", async () => {
    await render('h1');
    expect(
      api()
        .getEvents()
        .map((e) => e.title),
    ).toEqual(['Alice Patient']);

    // The refetch for the new provider has not answered yet.
    calendarSvc.getRange.and.returnValue(new Subject<AppointmentCalendarEvent[]>());
    pickProvider('s-9', 'Dr Nine');

    expect(api().getEvents()).toEqual([]);
    expect((grid() as HTMLElement).textContent).not.toContain('Alice Patient');
  });

  it('leaves the grid empty, never stale, when a refetch fails', async () => {
    await render('h1');
    expect(api().getEvents().length).toBe(1);

    calendarSvc.getRange.and.returnValue(throwError(() => new Error('500')));
    api().refetchEvents(); // same filter: only the failure handler can clear it
    fixture.detectChanges();

    expect(errorBanner()).not.toBeNull();
    expect(api().getEvents()).toEqual([]);
    expect((grid() as HTMLElement).textContent).not.toContain('Alice Patient');
  });

  it('renders the inline error state when the calendar service errors', async () => {
    await configure('h1');
    calendarSvc.getRange.and.returnValue(throwError(() => new Error('500')));
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();
    await calendarReady(); // the real calendar fetches its first range here
    fixture.detectChanges();

    expect(calendarSvc.getRange).toHaveBeenCalledTimes(1);
    expect(errorBanner()).not.toBeNull();
  });

  it('retries from the error banner', async () => {
    await configure('h1');
    calendarSvc.getRange.and.returnValue(throwError(() => new Error('500')));
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();
    await calendarReady();
    fixture.detectChanges();

    calendarSvc.getRange.and.returnValue(of([sample]));
    const retry: HTMLButtonElement = fixture.nativeElement.querySelector(
      '[data-testid="appointment-calendar-retry"]',
    );
    expect(retry).not.toBeNull();
    retry.click();
    fixture.detectChanges();

    expect(calendarSvc.getRange).toHaveBeenCalledTimes(2);
    expect(errorBanner()).toBeNull();
    expect(api().getEvents().length).toBe(1);
  });

  it('fetches again on a view change after a failed fetch', async () => {
    // With lazy fetching, FullCalendar counts a failed range as fetched and a
    // view inside it (Week -> Day) would never ask again. The failure arrives
    // after the first render, as an HTTP error does.
    await configure('h1');
    const pending = new Subject<AppointmentCalendarEvent[]>();
    calendarSvc.getRange.and.returnValue(pending);
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();
    await calendarReady();
    pending.error(new Error('500'));
    fixture.detectChanges();
    expect(errorBanner()).not.toBeNull();
    expect(calendarSvc.getRange).toHaveBeenCalledTimes(1);

    calendarSvc.getRange.and.returnValue(of([sample]));
    api().changeView('timeGridDay', nine);
    fixture.detectChanges();

    expect(calendarSvc.getRange).toHaveBeenCalledTimes(2);
    expect(errorBanner()).toBeNull();
  });

  it('does not refetch on a view change within a loaded range', async () => {
    // Lazy fetching stays on: Week -> Day inside the week already loaded
    // reuses it.
    await render('h1');
    expect(calendarSvc.getRange).toHaveBeenCalledTimes(1);

    api().changeView('timeGridDay', nine);
    fixture.detectChanges();

    expect(calendarSvc.getRange).toHaveBeenCalledTimes(1);
  });

  it('fetches the whole six-week month grid in one request', async () => {
    // The month grid is always six weeks; the server must accept that range
    // (AppointmentController.CALENDAR_MAX_RANGE_DAYS = 42).
    await render('h1');
    api().changeView('dayGridMonth', nine);
    fixture.detectChanges();

    const [, from, to] = calendarSvc.getRange.calls.mostRecent().args;
    const view = api().view;
    const lastCell = new Date(view.activeEnd.getTime() - 86_400_000);
    const iso = (d: Date): string => local(d).slice(0, 10);
    expect(from).toBe(iso(view.activeStart));
    expect(to).toBe(iso(lastCell));
    const days = Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000) + 1;
    expect(days).toBe(42);
  });

  it('shows the loading cue while the locale loads, not a blank page', async () => {
    await configure('h1');
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();

    expect(grid()).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar-loading"]'),
    ).not.toBeNull();
    await calendarReady();
  });

  it('keeps a fixed-height status line so the grid does not move while loading', async () => {
    await render('h1');
    const status: HTMLElement = fixture.nativeElement.querySelector('.cal-page__status');
    expect(status).not.toBeNull();
    expect(status.getAttribute('role')).toBe('status');
    const top = (grid() as HTMLElement).getBoundingClientRect().top;

    calendarSvc.getRange.and.returnValue(new Subject<AppointmentCalendarEvent[]>());
    api().refetchEvents();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar-loading"]'),
    ).not.toBeNull();
    expect((grid() as HTMLElement).getBoundingClientRect().top).toBe(top);
  });

  it('hands FullCalendar an Error that keeps the HTTP failure', async () => {
    await render('h1');
    const response = new HttpErrorResponse({
      status: 503,
      statusText: 'Service Unavailable',
      url: '/appointments/calendar',
    });
    calendarSvc.getRange.and.returnValue(throwError(() => response) as Observable<never>);
    const failure = jasmine.createSpy('failure');

    options()?.events?.(
      { start: nine, end: new Date(nine.getTime() + 86_400_000) },
      () => undefined,
      failure,
    );

    const error = failure.calls.mostRecent().args[0] as Error;
    expect(error.message).toContain('503');
    expect(error.message).not.toContain('[object Object]');
    expect(error.cause).toBe(response);
  });

  it('navigates to the appointment detail when an event is clicked', async () => {
    await render('h1');
    const navSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    options()?.eventClick?.({ event: { id: 'a-1' } });

    expect(navSpy).toHaveBeenCalledWith(['/appointments', 'a-1']);
  });

  it('passes the date and time of a time-grid slot to the new-appointment route', async () => {
    await render('h1');
    const navSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    options()?.dateClick?.({ date: new Date(2026, 9, 7, 10, 30), allDay: false });

    expect(navSpy).toHaveBeenCalledWith(['/appointments/new'], {
      queryParams: { date: '2026-10-07', time: '10:30' },
    });
  });

  it('passes only the date of an all-day (month) cell, never a midnight time', async () => {
    await render('h1');
    const navSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    options()?.dateClick?.({ date: new Date(2026, 9, 7), allDay: true });

    expect(navSpy).toHaveBeenCalledWith(['/appointments/new'], {
      queryParams: { date: '2026-10-07' },
    });
  });
});
