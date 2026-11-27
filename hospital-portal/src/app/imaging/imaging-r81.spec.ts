import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ImagingComponent } from './imaging';
import { ImagingOrderResponse, ImagingService } from '../services/imaging.service';
import { ImagingReadingService } from '../services/imaging-reading.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { EncounterService } from '../services/encounter.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';

/**
 * QA D4140 (r81, provisional): an imaging request placed by a nurse or a midwife waits for a
 * physician of the hospital. The row says so, a physician authorises or declines it, whoever asked
 * withdraws it, and nobody is offered a step of the exam before that.
 */
function pending(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o-pending',
    patientId: 'p1',
    patientDisplayName: 'Aissata ATest',
    hospitalId: 'h1',
    modality: 'CT',
    studyType: 'Scanner abdominal avec injection',
    priority: 'ROUTINE',
    status: 'DRAFT',
    orderingProviderUserId: 'u-nurse',
    orderingProviderName: 'Awa Infirmière',
    authorUserId: 'u-nurse',
    requestedByUserId: 'u-nurse',
    requestedByName: 'Awa Infirmière',
    physicianAuthorisationRequired: true,
    awaitingPhysicianAuthorisation: true,
    ...overrides,
  } as ImagingOrderResponse;
}

describe('ImagingComponent — r81 (D4140: a nurse’s request waits for a physician)', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imaging: jasmine.SpyObj<ImagingService>;
  let toast: jasmine.SpyObj<ToastService>;
  let confirm: jasmine.SpyObj<ConfirmService>;

  function setUp(roles: string[], me: string, orders: ImagingOrderResponse[]): void {
    imaging = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'getReportsByHospital',
      'checkDuplicates',
      'createOrder',
      'updateOrderStatus',
      'signOrder',
      'withdrawPendingRequest',
      'getReportsForOrder',
    ]);
    imaging.getReportsForOrder.and.returnValue(of([]));
    imaging.getAllOrders.and.returnValue(of(orders));
    imaging.getReportsByHospital.and.returnValue(of([]));
    imaging.checkDuplicates.and.returnValue(of([]));
    imaging.signOrder.and.returnValue(
      of(pending({ status: 'ORDERED', awaitingPhysicianAuthorisation: false })),
    );
    imaging.updateOrderStatus.and.returnValue(of(pending({ status: 'CANCELLED' })));
    imaging.withdrawPendingRequest.and.returnValue(of(pending({ status: 'CANCELLED' })));
    const encounters = jasmine.createSpyObj('EncounterService', ['list']);
    encounters.list.and.returnValue(of([]));
    const reading = jasmine.createSpyObj('ImagingReadingService', ['toSign', 'readingContext']);
    reading.toSign.and.returnValue(of([]));
    reading.readingContext.and.returnValue(of(null));
    const hospital = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospital.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'CHU' } as HospitalResponse),
    );
    const auth = jasmine.createSpyObj('AuthService', ['getHospitalId', 'getUserProfile']);
    auth.getHospitalId.and.returnValue('h1');
    auth.getUserProfile.and.returnValue({ id: me } as never);
    const patients = jasmine.createSpyObj('PatientService', ['list']);
    patients.list.and.returnValue(of([]));
    toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning', 'info']);
    confirm = jasmine.createSpyObj('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);

    TestBed.configureTestingModule({
      imports: [ImagingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ImagingService, useValue: imaging },
        { provide: ImagingReadingService, useValue: reading },
        { provide: HospitalService, useValue: hospital },
        { provide: PatientService, useValue: patients },
        { provide: EncounterService, useValue: encounters },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
        { provide: AuthService, useValue: auth },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => false,
            globalView: () => false,
            selectedHospitalId: () => 'h1',
            activeHospitalId: 'h1',
            hasAnyActiveRole: (wanted: string[]) => wanted.some((role) => roles.includes(role)),
          } as unknown as RoleContextService,
        },
      ],
    });
    fixture = TestBed.createComponent(ImagingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const q = (testid: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testid}"]`);

  it('the row reads « awaiting a physician », never « ordered » or « draft »', () => {
    setUp(['ROLE_IMAGING_TECHNICIAN'], 'u-tech', [pending()]);

    expect(q('imaging-row-awaiting-physician')).not.toBeNull();
    expect(q('imaging-row-status')).toBeNull();
  });

  it('the technician is offered no step of the exam on it', () => {
    setUp(['ROLE_IMAGING_TECHNICIAN'], 'u-tech', [pending()]);
    const order = pending();

    expect(component.canStartStudy(order)).toBeFalse();
    expect(component.canCompleteStudy(order)).toBeFalse();
    expect(component.canAuthoriseRequest(order)).toBeFalse();
    expect(component.canDeclineRequest(order)).toBeFalse();
    expect(component.canWithdrawRequest(order)).toBeFalse();
    expect(q('imaging-start-o-pending')).toBeNull();
    expect(q('imaging-authorise-o-pending')).toBeNull();
  });

  it('a doctor authorises it after a confirmation: the request is signed in their name', async () => {
    setUp(['ROLE_DOCTOR'], 'u-doc', [pending()]);
    expect(q('imaging-authorise-o-pending')).not.toBeNull();

    await component.authoriseRequest(pending());

    expect(confirm.ask).toHaveBeenCalled();
    expect(imaging.signOrder).toHaveBeenCalledWith(
      'o-pending',
      jasmine.objectContaining({ attestationConfirmed: true }),
    );
    expect(toast.success).toHaveBeenCalledWith('IMAGING.AUTHORISE.DONE');
  });

  it('a doctor who does not confirm authorises nothing', async () => {
    setUp(['ROLE_DOCTOR'], 'u-doc', [pending()]);
    confirm.ask.and.resolveTo(false);

    await component.authoriseRequest(pending());

    expect(imaging.signOrder).not.toHaveBeenCalled();
  });

  it('a doctor declines it with a reason, through the status endpoint', () => {
    setUp(['ROLE_DOCTOR'], 'u-doc', [pending()]);
    expect(q('imaging-decline-o-pending')).not.toBeNull();
    // The plain « cancel the order » is not offered on a request that waits: it is declined.
    expect(component.canCancelThisOrder(pending())).toBeFalse();

    component.declineRequest(pending());
    expect(component.cancelDialogTitleKey()).toBe('IMAGING.AUTHORISE.DECLINE');
    component.cancelReason.set('Pas indiqué : échographie d’abord');
    component.executeCancel();

    expect(imaging.updateOrderStatus).toHaveBeenCalledWith(
      'o-pending',
      jasmine.objectContaining({
        status: 'CANCELLED',
        cancellationReason: 'Pas indiqué : échographie d’abord',
      }),
    );
    expect(imaging.withdrawPendingRequest).not.toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('IMAGING.AUTHORISE.DECLINED');
    expect(component.cancelDialogTitleKey()).toBe('IMAGING.CANCEL_ORDER');
  });

  it('the nurse who asked withdraws her own request, with a reason, through her own door', () => {
    setUp(['ROLE_NURSE'], 'u-nurse', [pending()]);
    expect(q('imaging-withdraw-o-pending')).not.toBeNull();
    expect(q('imaging-authorise-o-pending')).toBeNull();
    expect(q('imaging-decline-o-pending')).toBeNull();

    component.withdrawRequest(pending());
    component.cancelReason.set('Mauvais patient');
    component.executeCancel();

    expect(imaging.withdrawPendingRequest).toHaveBeenCalledWith('o-pending', 'Mauvais patient');
    expect(imaging.updateOrderStatus).not.toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('IMAGING.AUTHORISE.WITHDRAWN');
  });

  it('another nurse is offered nothing on it, and reads it as before', () => {
    setUp(['ROLE_NURSE'], 'u-other-nurse', [pending()]);

    expect(q('imaging-withdraw-o-pending')).toBeNull();
    expect(component.canEditThisOrder(pending())).toBeFalse();
    expect(q('imaging-row-awaiting-physician')).not.toBeNull();
  });

  it('a nurse who places a request is told that it waits for a physician', () => {
    setUp(['ROLE_NURSE'], 'u-nurse', []);
    imaging.createOrder.and.returnValue(of(pending()));
    component.openCreate();
    component.onPatientPicked({ id: 'p1', firstName: 'Aissata', lastName: 'ATest' } as never);
    component.form.studyType = 'Radiographie thorax';

    component.submitForm();

    expect(toast.info).toHaveBeenCalledWith('IMAGING.AUTHORISE.PLACED_PENDING');
  });

  it('a doctor’s own order raises no such notice', () => {
    setUp(['ROLE_DOCTOR'], 'u-doc', []);
    imaging.createOrder.and.returnValue(
      of(pending({ status: 'ORDERED', awaitingPhysicianAuthorisation: false })),
    );
    component.openCreate();
    component.onPatientPicked({ id: 'p1', firstName: 'Aissata', lastName: 'ATest' } as never);
    component.form.studyType = 'Radiographie thorax';

    component.submitForm();

    expect(toast.info).not.toHaveBeenCalled();
  });

  it('the drawer names who asked, and no prescriber until a physician has signed', () => {
    setUp(['ROLE_DOCTOR'], 'u-doc', [pending()]);
    component.viewDetail(pending());
    fixture.detectChanges();

    expect(q('imaging-drawer-awaiting-physician')).not.toBeNull();
    expect(q('imaging-drawer-awaiting-notice')).not.toBeNull();
    expect(q('imaging-drawer-requested-by')?.textContent).toContain('Awa Infirmière');
    expect(q('imaging-drawer-prescriber')?.textContent).toContain(
      'IMAGING.AUTHORISE.NO_PRESCRIBER_YET',
    );
    expect(q('imaging-drawer-authorise')).not.toBeNull();
    expect(q('imaging-drawer-decline')).not.toBeNull();
    expect(q('imaging-drawer-start')).toBeNull();
  });

  it('once authorised, the drawer names the physician as prescriber and keeps who asked', () => {
    const authorised = pending({
      status: 'ORDERED',
      awaitingPhysicianAuthorisation: false,
      orderingProviderName: 'Dr Un',
      orderingProviderUserId: 'u-doc',
      providerSignedAt: '2026-11-27T10:00:00',
    });
    setUp(['ROLE_NURSE'], 'u-nurse', [authorised]);
    component.viewDetail(authorised);
    fixture.detectChanges();

    expect(q('imaging-drawer-awaiting-notice')).toBeNull();
    expect(q('imaging-drawer-prescriber')?.textContent).toContain('Dr Un');
    expect(q('imaging-drawer-requested-by')?.textContent).toContain('Awa Infirmière');
    // The order is the physician's now: whoever asked no longer withdraws it.
    expect(component.canWithdrawRequest(authorised)).toBeFalse();
    expect(q('imaging-row-status')).not.toBeNull();
  });

  it('once authorised, the technician is offered the start of the exam', () => {
    const authorised = pending({
      status: 'ORDERED',
      awaitingPhysicianAuthorisation: false,
      orderingProviderUserId: 'u-doc',
      providerSignedAt: '2026-11-27T10:00:00',
    });
    setUp(['ROLE_IMAGING_TECHNICIAN'], 'u-tech', [authorised]);

    expect(component.canStartStudy(authorised)).toBeTrue();
    expect(q('imaging-start-o-pending')).not.toBeNull();
  });

  it('the pending row and its actions fit the list at 1440, 768 and 390 px wide', () => {
    setUp(['ROLE_DOCTOR'], 'u-doc', [pending()]);
    const host = fixture.nativeElement as HTMLElement;
    for (const width of [1440, 768, 390]) {
      host.style.width = `${width}px`;
      fixture.detectChanges();
      const badge = q('imaging-row-awaiting-physician')!;
      expect(badge.scrollWidth)
        .withContext(`badge at ${width}px`)
        .toBeLessThanOrEqual(badge.clientWidth + 1);
    }
  });
});
