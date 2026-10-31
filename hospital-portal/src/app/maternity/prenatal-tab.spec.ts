import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';

import { PrenatalTabComponent } from './prenatal-tab';
import { PrenatalScheduleResponse, PrenatalService } from '../services/prenatal.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { roleContextStub } from '../testing/role-context.stub';
import { isSubmitBlocked } from '../shared/forms/submit-guard.testing';

/**
 * The prenatal schedule generator. Asserted through the rendered DOM in French
 * (the #661 shape): a visit type or a status badge only counts once the table
 * shows it translated, not when the model carries the key.
 */
describe('PrenatalTabComponent', () => {
  let fixture: ComponentFixture<PrenatalTabComponent>;
  let component: PrenatalTabComponent;
  let service: jasmine.SpyObj<PrenatalService>;
  let toast: jasmine.SpyObj<ToastService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');

  function schedule(overrides: Partial<PrenatalScheduleResponse> = {}): PrenatalScheduleResponse {
    return {
      patientId: 'p-1',
      hospitalId: 'h-1',
      currentGestationalWeek: 20,
      highRisk: false,
      recommendations: [
        { gestationalWeek: 20, durationMinutes: 30, visitType: 'ULTRASOUND', scheduled: false },
      ],
      existingAppointments: [
        {
          appointmentId: 'a-1',
          appointmentDate: '2026-10-01',
          startTime: '09:00',
          status: 'SCHEDULED',
          gestationalWeek: 24,
        },
      ],
      alerts: [],
      ...overrides,
    };
  }

  function generateFor(res: PrenatalScheduleResponse): void {
    service.schedule.and.returnValue(of(res));
    component.onPatientPicked({ id: 'p-1' } as PatientResponse);
    component.lmpDate = '2026-05-10';
    component.generate();
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<PrenatalService>('PrenatalService', [
      'schedule',
      'reschedule',
      'sendReminder',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    const auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getHospitalId',
      'getUserProfile',
    ]);
    auth.getUserProfile.and.returnValue(null);

    TestBed.configureTestingModule({
      imports: [PrenatalTabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PrenatalService, useValue: service },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_MIDWIFE'],
          }),
        },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PRENATAL: {
        RECOMMENDATIONS: 'Visites recommandées',
        VISIT_ULTRASOUND: 'Échographie',
        SCHEDULED: 'Planifiée',
        UNSCHEDULED: 'Non planifiée',
        EXISTING: 'Rendez-vous existants',
        REQUIRED_FIELDS: 'La patiente et la date des dernières règles sont requises',
        GENERATE_ERROR: 'Échec de la génération du calendrier',
        RESCHEDULED: 'Rendez-vous reprogrammé',
        BOOK: 'Planifier',
        BOOK_REASON: 'Consultation prénatale (CPN) — {{week}} SA',
      },
    });

    fixture = TestBed.createComponent(PrenatalTabComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('shows nothing but the form until a schedule is generated', () => {
    expect(host().querySelector('table')).toBeNull();
    const generate = host().querySelector('.modal-actions .btn-primary') as HTMLButtonElement;
    expect(isSubmitBlocked(generate)).toBeTrue();
  });

  it('refuses to generate without a patient and an LMP, in French', () => {
    component.generate();
    expect(service.schedule).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(
      'La patiente et la date des dernières règles sont requises',
    );
  });

  it('sends the patient, the hospital in scope and the LMP', () => {
    generateFor(schedule());
    expect(service.schedule).toHaveBeenCalledWith(
      jasmine.objectContaining({
        patientId: 'p-1',
        hospitalId: 'h-1',
        lastMenstrualPeriodDate: '2026-05-10',
      }),
    );
  });

  it('renders the recommended visits with translated type and status', () => {
    generateFor(schedule());
    expect(texts('h3.section-heading')).toEqual(['Visites recommandées', 'Rendez-vous existants']);
    const firstTable = host().querySelectorAll('table')[0];
    const cells = Array.from(firstTable.querySelectorAll('tbody td')).map((td) =>
      td.textContent?.trim(),
    );
    expect(cells[0]).toBe('20');
    expect(cells[1]).toBe('Échographie');
    expect(cells[4]).toBe('Non planifiée');
  });

  it('lists the alerts the schedule carries', () => {
    generateFor(schedule({ alerts: ['alert-text'] }));
    expect(texts('.prenatal-alert')[0]).toContain('alert-text');
  });

  it('toasts the translated error when generation fails', () => {
    service.schedule.and.returnValue(throwError(() => new Error('500')));
    component.onPatientPicked({ id: 'p-1' } as PatientResponse);
    component.lmpDate = '2026-05-10';
    component.generate();
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalledWith('Échec de la génération du calendrier');
    expect(host().querySelector('.loading-state')).toBeNull();
  });

  it('QA D732: shows the server French reason on a refused schedule, not the generic text', () => {
    const reason = 'La date des dernières règles ne peut pas être dans le futur.';
    service.schedule.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { message: reason } })),
    );
    component.onPatientPicked({ id: 'p-1' } as PatientResponse);
    component.lmpDate = '2027-05-10';
    component.generate();
    expect(toast.error).toHaveBeenCalledWith(reason);
  });

  it('QA D746: the real validation body shows the LMP field reason, and the picker stops at today', () => {
    // Round 10 re-test: the 400 carries the generic text as `message` and the
    // reason under `fieldErrors.lastMenstrualPeriodDate`.
    const reason = 'La date des dernières règles ne peut pas être dans le futur';
    service.schedule.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: {
              message: 'La demande contient des erreurs de validation',
              fieldErrors: { lastMenstrualPeriodDate: reason },
            },
          }),
      ),
    );
    component.onPatientPicked({ id: 'p-1' } as PatientResponse);
    component.lmpDate = '2027-01-01';
    component.generate();
    expect(toast.error).toHaveBeenCalledWith(reason);

    fixture.detectChanges();
    const lmp = host().querySelector<HTMLInputElement>('#pn-lmp');
    expect(lmp?.getAttribute('max')).toBe(component.today);
  });

  it('QA D732: a 5xx body never leaks in place of the translated fallback', () => {
    service.schedule.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 500, error: { message: 'NullPointerException' } }),
      ),
    );
    component.onPatientPicked({ id: 'p-1' } as PatientResponse);
    component.lmpDate = '2026-05-10';
    component.generate();
    expect(toast.error).toHaveBeenCalledWith('Échec de la génération du calendrier');
  });

  it('QA D732: the reschedule and reminder refusals also carry the server reason', () => {
    generateFor(schedule());
    service.reschedule.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 409, error: { message: 'Créneau déjà pris.' } }),
      ),
    );
    component.openReschedule(schedule().existingAppointments[0]);
    component.rescheduleDate = '2026-10-08';
    component.rescheduleTime = '10:30';
    component.submitReschedule();
    expect(toast.error).toHaveBeenCalledWith('Créneau déjà pris.');

    service.sendReminder.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 400, error: { message: 'Rappel refusé.' } }),
      ),
    );
    component.openReminder(schedule().existingAppointments[0]);
    component.submitReminder();
    expect(toast.error).toHaveBeenCalledWith('Rappel refusé.');
  });

  it('moves a rescheduled appointment in place, without regenerating', () => {
    generateFor(schedule());
    service.reschedule.and.returnValue(of({} as never));
    component.openReschedule(schedule().existingAppointments[0]);
    component.rescheduleDate = '2026-10-08';
    component.rescheduleTime = '10:30';
    component.submitReschedule();
    fixture.detectChanges();

    expect(service.schedule).toHaveBeenCalledTimes(1);
    expect(toast.success).toHaveBeenCalledWith('Rendez-vous reprogrammé');
    const existing = host().querySelectorAll('table')[1];
    const cells = Array.from(existing.querySelectorAll('tbody td')).map((td) =>
      td.textContent?.trim(),
    );
    expect(cells[2]).toBe('10:30');
    expect(host().querySelector('[role="dialog"]')).toBeNull();
  });
  it('QA D810: an unplanned visit offers Book, prefilled with the patient, the day and an antenatal reason', () => {
    const future = new Date();
    future.setDate(future.getDate() + 30);
    const target = `${future.getFullYear()}-${String(future.getMonth() + 1).padStart(2, '0')}-${String(future.getDate()).padStart(2, '0')}`;
    generateFor(
      schedule({
        recommendations: [
          {
            gestationalWeek: 24,
            durationMinutes: 30,
            visitType: 'ROUTINE_CHECK',
            scheduled: false,
            targetDate: target,
            suggestedStartTime: '09:30:00',
          },
          { gestationalWeek: 28, durationMinutes: 30, visitType: 'ROUTINE_CHECK', scheduled: true },
        ],
      }),
    );

    const links = component.bookLinks();
    expect(links[0]).toEqual({
      patientId: 'p-1',
      date: target,
      time: '09:30',
      reason: 'Consultation prénatale (CPN) — 24 SA',
      // QA D1055: the recommended length travels with the link.
      duration: '30',
    });
    // The scheduler counts a reason naming antenatal care as the visit.
    expect(links[0]?.['reason']).toMatch(/pr[ée]natal/i);
    expect(links[1]).toBeNull();
    const book = host().querySelectorAll('a[href^="/appointments/new"]');
    expect(book.length).toBe(1);
    expect(book[0].textContent).toContain('Planifier');
  });

  it('QA D1055 / D1031: Book carries the first-visit length and the provider; the contacts held are shown', () => {
    generateFor(
      schedule({
        staffId: 'staff-midwife',
        completedContacts: 2,
        recommendations: [
          {
            gestationalWeek: 12,
            durationMinutes: 45,
            visitType: 'INITIAL_INTAKE',
            scheduled: false,
          },
        ],
      }),
    );
    const link = component.bookLinks()[0];
    expect(link?.['duration']).toBe('45');
    expect(link?.['staffId']).toBe('staff-midwife');
    expect(host().querySelector('[data-testid="anc-contacts"]')).toBeTruthy();
  });

  it('QA D810: a recommended day already past is booked from today', () => {
    generateFor(
      schedule({
        recommendations: [
          {
            gestationalWeek: 12,
            durationMinutes: 30,
            visitType: 'INITIAL_INTAKE',
            scheduled: false,
            targetDate: '2020-01-01',
          },
        ],
      }),
    );
    const today = new Date();
    const expected = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    expect(component.bookLinks()[0]?.['date']).toBe(expected);
  });
});
