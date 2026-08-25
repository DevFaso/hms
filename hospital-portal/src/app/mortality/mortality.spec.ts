import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MortalityComponent } from './mortality';
import {
  DeathRecordResponse,
  MortalityRegister,
  MortalityService,
  RecordDeathResponse,
} from '../services/mortality.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

function mockDeath(overrides: Partial<DeathRecordResponse> = {}): DeathRecordResponse {
  return {
    id: 'd1',
    patientId: 'p1',
    patientName: 'Awa Traoré',
    diedAt: '2026-08-20T04:00:00',
    placeOfDeath: 'FACILITY',
    mannerOfDeath: 'NATURAL',
    immediateCause: 'Hypovolaemic shock',
    maternalDeath: false,
    whoMaternalDeath: false,
    perinatalDeath: false,
    autopsyRequested: false,
    amended: false,
    ...overrides,
  } as DeathRecordResponse;
}

function mockRegister(overrides: Partial<MortalityRegister> = {}): MortalityRegister {
  return {
    from: '2026-08-01',
    to: '2026-08-25',
    totalDeaths: 1,
    maternalDeaths: 0,
    lateMaternalDeaths: 0,
    perinatalDeaths: 0,
    stillbirths: 0,
    deaths: [mockDeath()],
    ...overrides,
  } as MortalityRegister;
}

describe('MortalityComponent', () => {
  let component: MortalityComponent;
  let mortalitySpy: jasmine.SpyObj<MortalityService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    mortalitySpy = jasmine.createSpyObj('MortalityService', [
      'getRegister',
      'recordDeath',
      'amendDeathRecord',
      'getForPatient',
    ]);
    mortalitySpy.getRegister.and.returnValue(of(mockRegister()));

    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const roleCtx = {
      hasAnyActiveRole: () => true,
      isSuperAdmin: () => false,
      activeHospitalId: 'h1',
    } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [MortalityComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MortalityService, useValue: mortalitySpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    }).compileComponents();

    component = TestBed.createComponent(MortalityComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('defaults the period to the current month and loads the register', () => {
    component.ngOnInit();
    expect(mortalitySpy.getRegister).toHaveBeenCalled();
    // Local date, not toISOString — that shifts to UTC and can report the
    // wrong day either side of midnight.
    expect(component.from).toMatch(/^\d{4}-\d{2}-01$/);
    expect(component.to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(component.register()?.totalDeaths).toBe(1);
  });

  it('surfaces a load failure rather than showing an empty register', () => {
    mortalitySpy.getRegister.and.returnValue(throwError(() => new Error('500')));
    component.ngOnInit();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.register()).toBeNull();
  });

  it('asks before recording a death and does nothing when declined', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Sepsis';
    component.submitRecord();
    expect(mortalitySpy.recordDeath).not.toHaveBeenCalled();
  });

  it('does not submit without a patient, a time, or an immediate cause', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '';
    component.form.immediateCause = 'Sepsis';
    component.submitRecord();
    expect(mortalitySpy.recordDeath).not.toHaveBeenCalled();
  });

  it('records a death and reports what the cascade closed', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    const response: RecordDeathResponse = {
      deathRecord: mockDeath(),
      closure: {
        admissionsClosed: 1,
        encountersClosed: 0,
        appointmentsCancelled: 3,
        recallsClosed: 1,
      },
    };
    mortalitySpy.recordDeath.and.returnValue(of(response));

    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = '  Sepsis  ';
    component.submitRecord();

    const [payload] = mortalitySpy.recordDeath.calls.mostRecent().args;
    expect(payload.immediateCause).toBe('Sepsis');
    expect(component.showRecordModal()).toBeFalse();
    // The cascade is reported, never silent.
    expect(component.lastClosure()?.appointmentsCancelled).toBe(3);
  });

  it('omits the maternal timing when the death is not maternal', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    mortalitySpy.recordDeath.and.returnValue(
      of({ deathRecord: mockDeath(), closure: {} } as RecordDeathResponse),
    );
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Sepsis';
    component.form.maternalDeath = false;
    component.submitRecord();

    const [payload] = mortalitySpy.recordDeath.calls.mostRecent().args;
    expect(payload.maternalDeathTiming).toBeUndefined();
  });

  it('sends the maternal timing when the death is maternal', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    mortalitySpy.recordDeath.and.returnValue(
      of({ deathRecord: mockDeath(), closure: {} } as RecordDeathResponse),
    );
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Postpartum haemorrhage';
    component.form.maternalDeath = true;
    component.form.maternalDeathTiming = 'WITHIN_42_DAYS_POSTPARTUM';
    component.submitRecord();

    const [payload] = mortalitySpy.recordDeath.calls.mostRecent().args;
    expect(payload.maternalDeathTiming).toBe('WITHIN_42_DAYS_POSTPARTUM');
  });

  it('reports a rejected recording rather than pretending it worked', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    mortalitySpy.recordDeath.and.returnValue(throwError(() => new Error('409')));
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Sepsis';
    component.submitRecord();

    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.recording()).toBeFalse();
  });

  it('does not amend without a reason', () => {
    component.openAmend(mockDeath());
    component.amendForm.amendmentReason = '   ';
    component.submitAmend();
    expect(mortalitySpy.amendDeathRecord).not.toHaveBeenCalled();
  });

  it('amends with the reason and refreshes', () => {
    mortalitySpy.amendDeathRecord.and.returnValue(of(mockDeath({ amended: true })));
    component.openAmend(mockDeath());
    component.amendForm.amendmentReason = 'Post-mortem findings';
    component.submitAmend();

    const [id, payload] = mortalitySpy.amendDeathRecord.calls.mostRecent().args;
    expect(id).toBe('d1');
    expect(payload.amendmentReason).toBe('Post-mortem findings');
    expect(component.selected()?.amended).toBeTrue();
    expect(component.showAmendModal()).toBeFalse();
  });

  it('knows when the cascade closed nothing worth reporting', () => {
    expect(
      component.closureHasContent({
        admissionsClosed: 0,
        encountersClosed: 0,
        appointmentsCancelled: 0,
        recallsClosed: 0,
      }),
    ).toBeFalse();
    expect(
      component.closureHasContent({
        admissionsClosed: 0,
        encountersClosed: 0,
        appointmentsCancelled: 1,
        recallsClosed: 0,
      }),
    ).toBeTrue();
  });

  it('tracks the picked patient onto the form', () => {
    component.onPatientPicked({ id: 'p9' } as PatientResponse);
    expect(component.form.patientId).toBe('p9');
    component.onPatientPicked(null);
    expect(component.form.patientId).toBe('');
  });
});
