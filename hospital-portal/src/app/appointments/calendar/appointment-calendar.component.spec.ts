import { computed } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

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

  const sample: AppointmentCalendarEvent = {
    id: 'a-1',
    patientId: 'p-1',
    patientName: 'Alice Patient',
    resourceId: 's-1',
    resourceName: 'Dr Provider',
    title: 'Alice Patient',
    start: '2026-05-03T09:00:00',
    end: '2026-05-03T09:30:00',
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

  /** configure → create → first render; returns the FullCalendar host. */
  async function render(activeHospital: string | null = 'h1'): Promise<HTMLElement> {
    await configure(activeHospital);
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('[data-testid="appointment-calendar-grid"]');
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

  interface ClickOptions {
    eventClick?: (a: { event: { id: string } }) => void;
    dateClick?: (a: { date: Date; allDay: boolean }) => void;
  }

  function options(): ClickOptions {
    return (fixture.componentInstance as unknown as { calendarOptions: ClickOptions })
      .calendarOptions;
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
    const grid = await render('h1');
    const buttons = Array.from(grid.querySelectorAll('button'));
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
    const grid = await render('h1');
    const tabs = Array.from(grid.querySelectorAll<HTMLElement>('[role="tab"]'));
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
      const grid = await render('h1');
      // Visible text and accessible names (prev/next are icon-only).
      return Array.from(grid.querySelectorAll('button')).flatMap((b) => [
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

    it('English (built in; must not fall back to the first loaded locale)', async () => {
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

    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar-error"]'),
    ).not.toBeNull();
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

    const select: HTMLSelectElement = fixture.nativeElement.querySelector(
      '[data-testid="appointment-calendar-provider"]',
    );
    (
      fixture.componentInstance as unknown as { providers: { set(v: unknown[]): void } }
    ).providers.set([{ id: 's-9', name: 'Dr Nine' }]);
    fixture.detectChanges();
    select.value = select.options[1].value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(calendarSvc.getRange).toHaveBeenCalledTimes(2);
    expect(calendarSvc.getRange.calls.mostRecent().args).toEqual(['h1', from, to, 's-9']);
  });

  it('renders the inline error state when the calendar service errors', async () => {
    await configure('h1');
    calendarSvc.getRange.and.returnValue(throwError(() => new Error('500')));
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges(); // the real calendar fetches its first range here
    fixture.detectChanges();

    expect(calendarSvc.getRange).toHaveBeenCalledTimes(1);
    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar-error"]'),
    ).not.toBeNull();
  });

  it('navigates to the appointment detail when an event is clicked', async () => {
    await render('h1');
    const navSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    options().eventClick?.({ event: { id: 'a-1' } });

    expect(navSpy).toHaveBeenCalledWith(['/appointments', 'a-1']);
  });

  it('pre-fills the date and time from a time-grid slot', async () => {
    await render('h1');
    const navSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    options().dateClick?.({ date: new Date(2026, 9, 7, 10, 30), allDay: false });

    expect(navSpy).toHaveBeenCalledWith(['/appointments/new'], {
      queryParams: { date: '2026-10-07', time: '10:30' },
    });
  });

  it('pre-fills only the date from an all-day (month) cell, never a midnight time', async () => {
    await render('h1');
    const navSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    options().dateClick?.({ date: new Date(2026, 9, 7), allDay: true });

    expect(navSpy).toHaveBeenCalledWith(['/appointments/new'], {
      queryParams: { date: '2026-10-07' },
    });
  });
});
