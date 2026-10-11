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

  it('renders the calendar shell when a hospital is active', async () => {
    await configure('h1');
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();

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
    await configure('h1');
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();

    const grid: HTMLElement = fixture.nativeElement.querySelector(
      '[data-testid="appointment-calendar-grid"]',
    );
    const buttons = Array.from(grid.querySelectorAll('button'));
    expect(buttons.length).toBeGreaterThan(0);

    // What a token resolves to, measured the same way as the calendar's own
    // elements so the comparison is like for like. The probe sits directly
    // in <body>, where FullCalendar portals its "+N more" popover: it only
    // sees the --fc-classic-* tokens if they are not scoped to the page.
    const resolve = (value: string): string => {
      const probe = document.createElement('span');
      probe.style.backgroundColor = value;
      document.body.appendChild(probe);
      const color = getComputedStyle(probe).backgroundColor;
      probe.remove();
      return color;
    };
    const primary = resolve('var(--primary)');
    expect(primary).not.toBe('rgba(0, 0, 0, 0)');

    const backgrounds = buttons.map((b) => getComputedStyle(b).backgroundColor);
    expect(backgrounds).toContain(primary);

    // Portaled content inherits the theme.
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
      const grid: HTMLElement = fixture.nativeElement.querySelector(
        '[data-testid="appointment-calendar-grid"]',
      );
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
    await configure(null);
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar-error"]'),
    ).not.toBeNull();
  });

  it('refetches the visible range for the new provider when the filter changes', async () => {
    // FullCalendar fires datesSet only when the visible dates change, so the
    // filter cannot wait for one: it must reload the range on screen itself.
    await configure('h1');
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();
    // The real calendar rendered and reported its range exactly once; feeding
    // the events back in did not trigger another datesSet (no reload loop).
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
    fixture.detectChanges();

    // Force a datesSet by invoking loadRange via the calendarOptions hook —
    // simulate FullCalendar emitting the visible range.
    const opts = (
      fixture.componentInstance as unknown as {
        calendarOptions: { datesSet?: (a: { start: Date; end: Date }) => void };
      }
    ).calendarOptions;
    opts.datesSet?.({
      start: new Date('2026-05-01T00:00:00'),
      end: new Date('2026-05-08T00:00:00'),
    });
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar-error"]'),
    ).not.toBeNull();
  });

  it('navigates to the appointment detail when an event is clicked', async () => {
    await configure('h1');
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();
    const navSpy = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    const opts = (
      fixture.componentInstance as unknown as {
        calendarOptions: { eventClick?: (a: { event: { id: string } }) => void };
      }
    ).calendarOptions;
    opts.eventClick?.({ event: { id: 'a-1' } });

    expect(navSpy).toHaveBeenCalledWith(['/appointments', 'a-1']);
  });
});
