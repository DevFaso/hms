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
      activeHospitalId: activeHospital,
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

  it('shows the error state when no hospital is active', async () => {
    await configure(null);
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="appointment-calendar-error"]'),
    ).not.toBeNull();
  });

  it('clears events on provider filter change so the next datesSet refetches', async () => {
    await configure('h1');
    fixture = TestBed.createComponent(AppointmentCalendarComponent);
    fixture.detectChanges();

    // Pre-load some events so we can verify the clear.
    (
      fixture.componentInstance as unknown as { calendarOptions: { events: unknown } }
    ).calendarOptions.events = [{ id: 'stale' }];

    (
      fixture.componentInstance as unknown as { onProviderChange(v: string): void }
    ).onProviderChange('s-9');

    expect(
      (fixture.componentInstance as unknown as { calendarOptions: { events: unknown[] } })
        .calendarOptions.events,
    ).toEqual([]);
  });

  it('surfaces an error toast/state when the calendar service errors', async () => {
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
