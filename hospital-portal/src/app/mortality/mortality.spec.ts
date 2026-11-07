import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { MortalityComponent } from './mortality';
import {
  DeathDispute,
  DeathRecordResponse,
  MortalityRegister,
  MortalityService,
  RecordDeathResponse,
} from '../services/mortality.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

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

describe('MortalityComponent, QA D1488 / D1490', () => {
  let fixtureComponent: MortalityComponent;
  let spy: jasmine.SpyObj<MortalityService>;
  let confirmSpy: jasmine.SpyObj<ConfirmService>;

  beforeEach(async () => {
    spy = jasmine.createSpyObj('MortalityService', [
      'getRegister',
      'recordDeath',
      'amendDeathRecord',
      'getForPatient',
      'markEnteredInError',
      'openDisputes',
      'upholdDeath',
    ]);
    spy.openDisputes.and.returnValue(of([]));
    spy.upholdDeath.and.returnValue(of({} as DeathDispute));
    spy.getRegister.and.returnValue(of(mockRegister()));
    spy.markEnteredInError.and.returnValue(of(mockDeath()));
    confirmSpy = jasmine.createSpyObj('ConfirmService', ['ask']);
    confirmSpy.ask.and.resolveTo(true);
    await TestBed.configureTestingModule({
      imports: [MortalityComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: MortalityService, useValue: spy },
        { provide: ConfirmService, useValue: confirmSpy },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: () => true,
            isSuperAdmin: () => false,
            activeHospitalId: 'h1',
          },
        },
      ],
    }).compileComponents();
    fixtureComponent = TestBed.createComponent(MortalityComponent).componentInstance;
  });

  it('hides and clears the maternal classification for a patient recorded male, unknown sex keeps it', () => {
    fixtureComponent.form.maternalDeath = true;
    fixtureComponent.onPatientPicked({ id: 'p1', gender: 'M' } as PatientResponse);
    expect(fixtureComponent.maternalHidden()).toBeTrue();
    expect(fixtureComponent.form.maternalDeath).toBeFalse();

    fixtureComponent.onPatientPicked({ id: 'p2' } as PatientResponse);
    expect(fixtureComponent.maternalHidden()).toBeFalse();
  });

  it('marks a record entered in error only with a reason and a confirmation', async () => {
    fixtureComponent.openEnteredInError(mockDeath());
    fixtureComponent.errorReason = '   ';
    await fixtureComponent.submitEnteredInError();
    expect(spy.markEnteredInError).not.toHaveBeenCalled();

    fixtureComponent.errorReason = ' Wrong patient ';
    await fixtureComponent.submitEnteredInError();
    expect(confirmSpy.ask).toHaveBeenCalled();
    expect(spy.markEnteredInError).toHaveBeenCalledWith(mockDeath().id, 'Wrong patient');
    expect(fixtureComponent.errorRecord()).toBeNull();
  });

  it('an empty reason is starred and marked, and nothing is sent', () => {
    const fixture = TestBed.createComponent(MortalityComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    fixture.componentInstance.openEnteredInError(mockDeath());
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="mort-entered-in-error-submit"]',
      sent: spy.markEnteredInError,
    });
    expect(marked).toContain('e-reason');
    fixture.nativeElement.remove();
  });

  it('entered in error from a dispute reaches a death outside the period shown', () => {
    fixtureComponent.register.set(mockRegister({ deaths: [] }));
    spy.getForPatient.and.returnValue(of(mockDeath()));
    fixtureComponent.openEnteredInErrorFor({ patientId: 'p1', deathRecordId: 'd1' } as DeathDispute);
    expect(spy.getForPatient).toHaveBeenCalledWith('p1');
    expect(fixtureComponent.errorRecord()?.id).toBe('d1');
  });

  it('upholding needs a reason: empty is marked and nothing is sent', () => {
    const fixture = TestBed.createComponent(MortalityComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    fixture.componentInstance.openUphold({ id: 'x1', patientName: 'Awa' } as DeathDispute);
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="mort-uphold-submit"]',
      sent: spy.upholdDeath,
    });
    expect(marked).toContain('u-reason');
    fixture.nativeElement.remove();
  });

  it('upholds with the reason after the confirmation, and reloads the disputes', async () => {
    fixtureComponent.openUphold({ id: 'x1' } as DeathDispute);
    fixtureComponent.upholdReason = ' Family confirmed ';
    spy.openDisputes.calls.reset();
    await fixtureComponent.submitUphold();
    expect(confirmSpy.ask).toHaveBeenCalled();
    expect(spy.upholdDeath).toHaveBeenCalledWith('x1', 'Family confirmed');
    expect(spy.openDisputes).toHaveBeenCalled();
  });

  it('sends nothing when the confirmation is declined', async () => {
    confirmSpy.ask.and.resolveTo(false);
    fixtureComponent.openEnteredInError(mockDeath());
    fixtureComponent.errorReason = 'Wrong patient';
    await fixtureComponent.submitEnteredInError();
    expect(spy.markEnteredInError).not.toHaveBeenCalled();
  });
});

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
      'openDisputes',
    ]);
    mortalitySpy.openDisputes.and.returnValue(of([]));
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
        provideHttpClient(withXhr()),
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

  it('QA D1451: the three boxes answer a real click, and the backdrop still closes the dialog', async () => {
    const fixture = TestBed.createComponent(MortalityComponent);
    const root = fixture.nativeElement as HTMLElement;
    const settle = async () => {
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    fixture.componentInstance.openRecord();
    await settle();
    const boxes = () => [
      ...root.querySelectorAll<HTMLInputElement>('.modal-body input[type="checkbox"]'),
    ];
    expect(boxes().length).toBe(3);
    for (const index of [0, 1, 2]) {
      boxes()[index].click();
      await settle();
      expect(boxes()[index].checked).withContext(`box ${index}`).toBeTrue();
      boxes()[index].closest('label')!.click();
      await settle();
      expect(boxes()[index].checked).withContext(`label ${index}`).toBeFalse();
      boxes()[index].click();
      await settle();
    }
    const form = fixture.componentInstance.form;
    expect([form.maternalDeath, form.perinatalDeath, form.autopsyRequested]).toEqual([
      true,
      true,
      true,
    ]);

    root.querySelector<HTMLElement>('.modal-header')!.click();
    await settle();
    expect(root.querySelector('.modal-backdrop')).not.toBeNull();
    root.querySelector<HTMLElement>('.modal-backdrop')!.click();
    await settle();
    expect(root.querySelector('.modal-backdrop')).toBeNull();
  });

  it('asks before recording a death and does nothing when declined', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Sepsis';
    await component.submitRecord();
    expect(mortalitySpy.recordDeath).not.toHaveBeenCalled();
  });

  it('does not submit without a patient, a time, or an immediate cause', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '';
    component.form.immediateCause = 'Sepsis';
    await component.submitRecord();
    expect(mortalitySpy.recordDeath).not.toHaveBeenCalled();
  });

  it('records a death and reports what the cascade closed', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
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
    await component.submitRecord();

    const [payload] = mortalitySpy.recordDeath.calls.mostRecent().args;
    expect(payload.immediateCause).toBe('Sepsis');
    // #948 review: the picker's wall clock goes out as the UTC the list reads
    // back through serverTime (not the raw '2026-08-24T10:00').
    expect(payload.diedAt).toBe(new Date(2026, 7, 24, 10, 0).toISOString().slice(0, 19));
    expect(component.showRecordModal()).toBeFalse();
    // The cascade is reported, never silent.
    expect(component.lastClosure()?.appointmentsCancelled).toBe(3);
  });

  it('omits the maternal timing when the death is not maternal', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    mortalitySpy.recordDeath.and.returnValue(
      of({ deathRecord: mockDeath(), closure: {} } as RecordDeathResponse),
    );
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Sepsis';
    component.form.maternalDeath = false;
    await component.submitRecord();

    const [payload] = mortalitySpy.recordDeath.calls.mostRecent().args;
    expect(payload.maternalDeathTiming).toBeUndefined();
  });

  it('sends the maternal timing when the death is maternal', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    mortalitySpy.recordDeath.and.returnValue(
      of({ deathRecord: mockDeath(), closure: {} } as RecordDeathResponse),
    );
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Postpartum haemorrhage';
    component.form.maternalDeath = true;
    component.form.maternalDeathTiming = 'WITHIN_42_DAYS_POSTPARTUM';
    await component.submitRecord();

    const [payload] = mortalitySpy.recordDeath.calls.mostRecent().args;
    expect(payload.maternalDeathTiming).toBe('WITHIN_42_DAYS_POSTPARTUM');
  });

  it('reports a rejected recording rather than pretending it worked', async () => {
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    mortalitySpy.recordDeath.and.returnValue(throwError(() => new Error('409')));
    component.openRecord();
    component.form.patientId = 'p1';
    component.form.diedAt = '2026-08-24T10:00';
    component.form.immediateCause = 'Sepsis';
    await component.submitRecord();

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
