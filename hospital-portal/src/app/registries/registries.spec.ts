import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { RegistriesComponent } from './registries';
import { ProgramEnrollment, ProgramRegistryService } from '../services/program-registry.service';
import { PatientService } from '../services/patient.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

function enrollment(overrides: Partial<ProgramEnrollment> = {}): ProgramEnrollment {
  return {
    id: 'e1',
    hospitalId: 'h1',
    patientId: 'p1',
    patientName: 'Awa Traore',
    mrn: 'MRN-1',
    phoneNumber: '+22670000001',
    program: 'HIV',
    status: 'ACTIVE',
    enrolledOn: '2026-07-01',
    enrolledByName: 'Nurse One',
    visitCadenceDays: 30,
    lastVisitOn: '2026-07-20',
    nextExpectedVisit: '2026-08-19',
    overdueDays: 13,
    notes: null,
    closedOn: null,
    closureReason: null,
    createdAt: '2026-07-01T09:00:00',
    ...overrides,
  };
}

/**
 * Disease registries (Tier 2 item 35).
 *
 * The load-failure case gets its own test because the house defect class it
 * guards against — an outage rendered as an empty cohort — is exactly the
 * lie a programme coordinator must never be told.
 */
describe('RegistriesComponent', () => {
  let fixture: ComponentFixture<RegistriesComponent>;
  let component: RegistriesComponent;
  let registryService: jasmine.SpyObj<ProgramRegistryService>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    registryService = jasmine.createSpyObj<ProgramRegistryService>('ProgramRegistryService', [
      'registry',
      'counts',
      'enroll',
      'updateStatus',
      'recordVisit',
      'patientEnrollments',
    ]);
    registryService.registry.and.returnValue(of([]));
    registryService.counts.and.returnValue(of({}));
    registryService.enroll.and.returnValue(of(enrollment()));
    registryService.updateStatus.and.returnValue(of(enrollment()));
    registryService.recordVisit.and.returnValue(of(enrollment()));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [RegistriesComponent, TranslateModule.forRoot()],
      providers: [
        { provide: ProgramRegistryService, useValue: registryService },
        { provide: PatientService, useValue: { list: () => of([]) } },
        { provide: RoleContextService, useValue: { activeHospitalId: 'h1' } },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RegistriesComponent);
    component = fixture.componentInstance;
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('loads the ACTIVE cohort of the first programme on init', () => {
    fixture.detectChanges();
    expect(registryService.registry).toHaveBeenCalledWith('HIV', 'ACTIVE');
    expect(registryService.counts).toHaveBeenCalledWith('HIV');
  });

  it('switching programme reloads that registry', () => {
    fixture.detectChanges();
    registryService.registry.calls.reset();

    component.setProgram('TB');
    expect(registryService.registry).toHaveBeenCalledWith('TB', 'ACTIVE');
  });

  it('renders an overdue row with its chip and the summary count', () => {
    registryService.registry.and.returnValue(of([enrollment()]));
    fixture.detectChanges();

    expect(root().querySelector('[data-testid="overdue-chip"]')).not.toBeNull();
    expect(root().querySelector('[data-testid="overdue-summary"]')).not.toBeNull();
    expect(root().querySelector('.overdue-row')).not.toBeNull();
  });

  it('a load failure renders the error state, never an empty cohort', () => {
    registryService.registry.and.returnValue(throwError(() => new Error('boom')));
    fixture.detectChanges();

    expect(root().querySelector('[data-testid="registry-error"]')).not.toBeNull();
    expect(root().querySelector('.empty-state')).toBeNull();
  });

  it('refuses to enrol without a patient and a cadence', () => {
    fixture.detectChanges();
    component.openEnrollForm();
    fixture.detectChanges();

    component.submitEnroll();

    expect(registryService.enroll).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('enrols with what the clinician typed and no invented defaults', () => {
    fixture.detectChanges();
    component.openEnrollForm();
    component.selectPatient({ id: 'p9', firstName: 'Moussa', lastName: 'Kone' } as never);
    component.cadenceDays.set(14);
    component.notes.set('Referred from CSPS');

    component.submitEnroll();

    expect(registryService.enroll).toHaveBeenCalledWith('p9', {
      program: 'HIV',
      visitCadenceDays: 14,
      notes: 'Referred from CSPS',
    });
  });

  it('the cadence field starts empty rather than prefilled', () => {
    fixture.detectChanges();
    component.openEnrollForm();
    expect(component.cadenceDays()).toBeNull();
  });

  it('recording a visit posts against the row patient and reloads', () => {
    registryService.registry.and.returnValue(of([enrollment()]));
    fixture.detectChanges();
    registryService.registry.calls.reset();

    component.recordVisit(enrollment());

    expect(registryService.recordVisit).toHaveBeenCalledWith('p1', 'e1');
    expect(registryService.registry).toHaveBeenCalled();
  });

  it('closing an enrolment demands a reason before calling the server', () => {
    fixture.detectChanges();
    component.openStatusModal(enrollment());
    component.newStatus.set('LOST_TO_FOLLOW_UP');
    component.statusReason.set('   ');

    component.submitStatus();

    expect(registryService.updateStatus).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
  });

  it('a move back to ACTIVE sends no reason field at all', () => {
    fixture.detectChanges();
    component.openStatusModal(enrollment({ status: 'WITHDRAWN' }));
    component.newStatus.set('ACTIVE');
    component.statusReason.set('should not be sent');

    component.submitStatus();

    expect(registryService.updateStatus).toHaveBeenCalledWith('p1', 'e1', { status: 'ACTIVE' });
  });

  it('surfaces the backend refusal verbatim rather than a generic failure', () => {
    fixture.detectChanges();
    component.openEnrollForm();
    component.selectPatient({ id: 'p9', firstName: 'Moussa', lastName: 'Kone' } as never);
    component.cadenceDays.set(30);
    registryService.enroll.and.returnValue(
      throwError(() => ({
        error: { message: 'The patient is already enrolled in this programme.' },
      })),
    );

    component.submitEnroll();

    expect(toast.error).toHaveBeenCalledWith('The patient is already enrolled in this programme.');
  });
});
