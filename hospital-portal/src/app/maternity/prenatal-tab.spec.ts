import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PrenatalTabComponent } from './prenatal-tab';
import { PrenatalScheduleResponse, PrenatalService } from '../services/prenatal.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { roleContextStub } from '../testing/role-context.stub';

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
      },
    });

    fixture = TestBed.createComponent(PrenatalTabComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('shows nothing but the form until a schedule is generated', () => {
    expect(host().querySelector('table')).toBeNull();
    const generate = host().querySelector('.modal-actions .btn-primary') as HTMLButtonElement;
    expect(generate.disabled).toBeTrue();
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
});
