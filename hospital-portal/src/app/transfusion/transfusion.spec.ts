import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { TransfusionComponent } from './transfusion';
import {
  BloodUnitResponse,
  CrossmatchResponse,
  PatientBloodGroupResponse,
  TransfusionAdministrationResponse,
  TransfusionReactionResponse,
  TransfusionRequestResponse,
  TransfusionService,
} from '../services/transfusion.service';
import { AdministrationWitnessCandidate, NurseTaskService } from '../services/nurse-task.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { AuthService } from '../auth/auth.service';
import { answerReasonDialog, reasonDialog } from '../shared/reason-dialog/reason-dialog.testing';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

function mockRequest(
  overrides: Partial<TransfusionRequestResponse> = {},
): TransfusionRequestResponse {
  return {
    id: 'req-1',
    patientId: 'p1',
    patientName: 'Awa Traoré',
    productType: 'PACKED_RED_CELLS',
    unitsRequested: 2,
    indication: 'Postpartum haemorrhage',
    urgency: 'ROUTINE',
    status: 'REQUESTED',
    screenCurrent: true,
    units: [],
    crossmatches: [],
    ...overrides,
  } as TransfusionRequestResponse;
}

function mockUnit(overrides: Partial<BloodUnitResponse> = {}): BloodUnitResponse {
  return {
    id: 'u1',
    unitNumber: 'BU-001',
    productType: 'PACKED_RED_CELLS',
    aboGroup: 'O',
    rhFactor: 'NEGATIVE',
    expiresOn: '2026-12-01',
    expired: false,
    status: 'AVAILABLE',
    ...overrides,
  } as BloodUnitResponse;
}

describe('TransfusionComponent', () => {
  let component: TransfusionComponent;
  let txSpy: jasmine.SpyObj<TransfusionService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    txSpy = jasmine.createSpyObj('TransfusionService', [
      'listRequests',
      'getRequest',
      'createRequest',
      'cancelRequest',
      'listUnits',
      'listAssignableUnits',
      'receiveUnit',
      'discardUnit',
      'recordBloodGroup',
      'getCurrentBloodGroup',
      'recordCrossmatch',
      'issueUnit',
      'startAdministration',
      'recordReaction',
      'listAdministrationsForPatient',
      'completeAdministration',
      'stopAdministration',
    ]);
    txSpy.listAdministrationsForPatient.and.returnValue(of([]));
    txSpy.listRequests.and.returnValue(of([mockRequest()]));
    txSpy.listUnits.and.returnValue(of([mockUnit()]));
    txSpy.listAssignableUnits.and.returnValue(of([mockUnit()]));

    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const roleCtx = roleContextStub({
      superAdmin: false,
      hospitalId: 'h1',
      roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_LAB_TECHNICIAN'],
    });

    await TestBed.configureTestingModule({
      imports: [TransfusionComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: TransfusionService, useValue: txSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    }).compileComponents();

    component = TestBed.createComponent(TransfusionComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // QA D1081: DOCTOR2_A was offered "Annuler" on DOCTOR_A's request (via
  // window.prompt) and refused only after typing the reason.
  it('D1081: offers Cancel on a request only to the clinician who asked for it', () => {
    spyOn(TestBed.inject(AuthService), 'getUserProfile').and.returnValue({ id: 'u-me' } as never);
    expect(component.canCancelRequest(mockRequest({ authorUserId: 'u-colleague' }))).toBeFalse();
    expect(component.canCancelRequest(mockRequest({ authorUserId: 'u-me' }))).toBeTrue();
    expect(
      component.canCancelRequest(mockRequest({ authorUserId: 'u-me', status: 'CANCELLED' })),
    ).toBeFalse();
  });

  it('loads requests on init', () => {
    component.ngOnInit();
    expect(txSpy.listRequests).toHaveBeenCalled();
    expect(component.requests().length).toBe(1);
    expect(component.loading()).toBeFalse();
  });

  it('lazily loads units when the tab is first opened', () => {
    component.setTab('units');
    expect(txSpy.listUnits).toHaveBeenCalled();

    txSpy.listUnits.calls.reset();
    component.setTab('requests');
    component.setTab('units');
    expect(txSpy.listUnits).not.toHaveBeenCalled();
  });

  it('re-fetches the full request when opening the detail panel', () => {
    // The list projection carries no units or crossmatches, so rendering the
    // panel from it would show an empty workup for a request that has one.
    const full = mockRequest({ units: [mockUnit({ status: 'CROSSMATCHED' })] });
    txSpy.getRequest.and.returnValue(of(full));

    component.openRequest(mockRequest());

    expect(txSpy.getRequest).toHaveBeenCalledWith('req-1');
    expect(component.selectedRequest()?.units.length).toBe(1);
  });

  it('surfaces the standing type and screen when a patient is picked', () => {
    const group = {
      aboGroup: 'A',
      rhFactor: 'POSITIVE',
      screenCurrent: true,
    } as PatientBloodGroupResponse;
    txSpy.getCurrentBloodGroup.and.returnValue(of(group));

    component.onPatientPicked({ id: 'p1' } as PatientResponse);

    expect(txSpy.getCurrentBloodGroup).toHaveBeenCalledWith('p1');
    expect(component.patientGroup()).toBe(group);
  });

  it('leaves the group empty when the patient has never been typed', () => {
    txSpy.getCurrentBloodGroup.and.returnValue(throwError(() => new Error('404')));

    component.onPatientPicked({ id: 'p1' } as PatientResponse);

    expect(component.patientGroup()).toBeNull();
  });

  it('creates a request and opens it', () => {
    txSpy.createRequest.and.returnValue(of(mockRequest()));
    component.openNewRequest();
    component.requestForm.patientId = 'p1';
    component.requestForm.indication = '  Severe anaemia  ';
    component.submitRequest();

    const [payload] = txSpy.createRequest.calls.mostRecent().args;
    expect(payload.indication).toBe('Severe anaemia');
    expect(component.showRequestModal()).toBeFalse();
    expect(component.selectedRequest()).toBeTruthy();
  });

  it('does not submit a request without a patient', () => {
    component.openNewRequest();
    component.requestForm.indication = 'Anaemia';
    component.submitRequest();
    expect(txSpy.createRequest).not.toHaveBeenCalled();
  });

  it('reports a refused crossmatch rather than pretending it worked', () => {
    // The ABO/Rh refusal is the most important failure on this page.
    txSpy.getRequest.and.returnValue(of(mockRequest()));
    txSpy.recordCrossmatch.and.returnValue(throwError(() => new Error('incompatible')));
    component.selectedRequest.set(mockRequest());
    component.openCrossmatch();
    component.crossmatchForm.bloodUnitId = 'u1';
    component.submitCrossmatch();

    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.crossmatchSubmitting()).toBeFalse();
  });

  it('records a compatible crossmatch and refreshes the request', () => {
    txSpy.recordCrossmatch.and.returnValue(of({ id: 'x1' } as CrossmatchResponse));
    txSpy.getRequest.and.returnValue(of(mockRequest({ status: 'CROSSMATCHED' })));
    component.selectedRequest.set(mockRequest());
    component.openCrossmatch();
    component.crossmatchForm.bloodUnitId = 'u1';
    component.submitCrossmatch();

    expect(txSpy.recordCrossmatch).toHaveBeenCalled();
    expect(component.selectedRequest()?.status).toBe('CROSSMATCHED');
  });

  it('splits units into issuable and hangable by status', () => {
    component.selectedRequest.set(
      mockRequest({
        units: [
          mockUnit({ id: 'a', status: 'CROSSMATCHED' }),
          mockUnit({ id: 'b', status: 'ISSUED' }),
          mockUnit({ id: 'c', status: 'TRANSFUSED' }),
        ],
      }),
    );
    expect(component.issuableUnits().map((u) => u.id)).toEqual(['a']);
    expect(component.hangableUnits().map((u) => u.id)).toEqual(['b']);
  });

  it('does not submit a hang without a second verifier', () => {
    component.selectedRequest.set(mockRequest());
    component.openHang(mockUnit({ status: 'ISSUED' }));
    component.hangForm.verifiedByUserId = '   ';
    component.submitHang();
    expect(txSpy.startAdministration).not.toHaveBeenCalled();
  });

  it('records a reaction and refreshes', () => {
    txSpy.recordReaction.and.returnValue(of({ id: 'rx1' } as TransfusionReactionResponse));
    txSpy.getRequest.and.returnValue(of(mockRequest()));
    component.selectedRequest.set(mockRequest());
    component.openReaction('adm-1');
    component.reactionForm.onsetAt = '2026-08-25T10:00';
    component.reactionForm.signsSymptoms = 'Fever and rigors';
    component.submitReaction();

    expect(txSpy.recordReaction).toHaveBeenCalled();
    expect(component.showReactionModal()).toBeFalse();
  });

  it('QA D858/D885: shows the server reason for a refused reaction, in the caller language', () => {
    txSpy.recordReaction.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          message: "L'heure de début de la réaction ne peut pas être dans le futur.",
        },
      })),
    );
    component.openReaction('adm-1');
    component.reactionForm.onsetAt = '2099-01-01T10:00';
    component.reactionForm.signsSymptoms = 'Fièvre 38,6 °C';
    component.submitReaction();

    expect(toastSpy.error).toHaveBeenCalledWith(
      "L'heure de début de la réaction ne peut pas être dans le futur.",
    );
    expect(component.showReactionModal()).toBeTrue();
  });

  it('QA D884/D885: shows the field reason for a refused unit count', () => {
    txSpy.createRequest.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          error: 'Validation Failed',
          message: 'Erreurs de validation dans la requête',
          fieldErrors: { unitsRequested: 'Une demande ne peut porter que sur 20 poches au plus.' },
        },
      })),
    );
    component.requestForm.patientId = 'p1';
    component.requestForm.unitsRequested = 500;
    component.requestForm.indication = 'x';
    component.submitRequest();

    expect(toastSpy.error).toHaveBeenCalledWith(
      'Une demande ne peut porter que sur 20 poches au plus.',
    );
  });

  it('QA D858/D883/D884: the pickers stop at today and now, the counts at the server limits', () => {
    component.openUnitModal();
    component.openReaction('adm-1');
    expect(component.maxCollectedOn).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(component.maxOnsetAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    expect(component.maxUnitsPerRequest).toBe(20);
    expect(component.maxUnitVolumeMl).toBe(1000);
  });

  it('does not record a reaction with no signs described', () => {
    component.openReaction('adm-1');
    component.reactionForm.onsetAt = '2026-08-25T10:00';
    component.reactionForm.signsSymptoms = '';
    component.submitReaction();
    expect(txSpy.recordReaction).not.toHaveBeenCalled();
  });

  // QA D1201 (round 22): the shared reason dialog, not window.prompt.
  it('cancelling a request needs a reason from the operator', () => {
    const fixture = TestBed.createComponent(TransfusionComponent);
    fixture.componentInstance.cancelRequest(mockRequest());
    expect(reasonDialog(fixture)).not.toBeNull();
    answerReasonDialog(fixture, '  ');
    expect(txSpy.cancelRequest).not.toHaveBeenCalled();
  });

  it('cancels with the supplied reason', () => {
    txSpy.cancelRequest.and.returnValue(of(mockRequest({ status: 'CANCELLED' })));
    const fixture = TestBed.createComponent(TransfusionComponent);
    fixture.componentInstance.cancelRequest(mockRequest());
    answerReasonDialog(fixture, 'Patient stabilised');
    expect(txSpy.cancelRequest).toHaveBeenCalledWith('req-1', 'Patient stabilised');
  });

  it('discarding a unit asks the reason in the shared dialog', () => {
    txSpy.discardUnit.and.returnValue(of(mockUnit()));
    const fixture = TestBed.createComponent(TransfusionComponent);
    fixture.componentInstance.discardUnit({ id: 'u-1' } as never);
    answerReasonDialog(fixture, '');
    expect(txSpy.discardUnit).not.toHaveBeenCalled();
    answerReasonDialog(fixture, 'Poche percée');
    expect(txSpy.discardUnit).toHaveBeenCalledWith('u-1', 'Poche percée');
  });

  it('maps statuses and urgency to display classes', () => {
    expect(component.statusClass('COMPLETED')).toBe('status-completed');
    expect(component.statusClass('CANCELLED')).toBe('status-cancelled');
    expect(component.statusClass('ISSUED')).toBe('status-progress');
    expect(component.urgencyClass('EMERGENCY')).toBe('urgency-emergency');
    expect(component.urgencyClass('ROUTINE')).toBe('urgency-routine');
  });
});

/**
 * The open O-to-B platelet question (product-owner decision, 2026-08-26:
 * keep it open and make it known).
 *
 * The pairing is PERMITTED and stays permitted — the sign-off said so. What
 * these pin is that the advisory appears where a blood-bank scientist will
 * see it, and that it never disguises itself as a compatibility problem.
 */
describe('TransfusionComponent — platelet pairing pending confirmation', () => {
  let fixture: ComponentFixture<TransfusionComponent>;
  let component: TransfusionComponent;
  let txSpy: jasmine.SpyObj<TransfusionService>;

  function crossmatch(overrides: Partial<CrossmatchResponse> = {}): CrossmatchResponse {
    return {
      id: 'xm-1',
      requestId: 'req-1',
      bloodUnitId: 'u1',
      unitNumber: 'BU-001',
      compatible: true,
      method: 'Immediate spin',
      incompatibilityReason: null,
      performedByName: 'Lab One',
      performedAt: '2026-08-26T09:00:00',
      expiresAt: '2026-08-27T09:00:00',
      usable: true,
      ...overrides,
    } as CrossmatchResponse;
  }

  beforeEach(async () => {
    txSpy = jasmine.createSpyObj('TransfusionService', [
      'listRequests',
      'getRequest',
      'createRequest',
      'cancelRequest',
      'listUnits',
      'listAssignableUnits',
      'receiveUnit',
      'discardUnit',
      'recordBloodGroup',
      'getCurrentBloodGroup',
      'recordCrossmatch',
      'issueUnit',
      'startAdministration',
      'recordReaction',
      'listAdministrationsForPatient',
      'completeAdministration',
      'stopAdministration',
    ]);
    txSpy.listAdministrationsForPatient.and.returnValue(of([]));
    txSpy.listRequests.and.returnValue(of([mockRequest()]));
    txSpy.listUnits.and.returnValue(of([]));
    txSpy.listAssignableUnits.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [TransfusionComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: TransfusionService, useValue: txSpy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_DOCTOR', 'ROLE_NURSE', 'ROLE_LAB_TECHNICIAN'],
          }),
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TransfusionComponent);
    component = fixture.componentInstance;
  });

  function showRequestWith(xm: CrossmatchResponse): void {
    component.selectedRequest.set(
      mockRequest({
        productType: 'PLATELETS',
        units: [mockUnit({ id: 'u1', aboGroup: 'O', productType: 'PLATELETS' })],
        crossmatches: [xm],
      }),
    );
    fixture.detectChanges();
  }

  it('shows the advisory on the pairing the protocol has not confirmed', () => {
    showRequestWith(crossmatch({ plateletPairingPendingConfirmation: true }));

    expect(
      fixture.nativeElement.querySelector('[data-testid="xm-pending-confirmation-u1"]'),
    ).not.toBeNull();
  });

  it('still reads as compatible — the advisory is not a compatibility problem', () => {
    // If this ever inverts, the software has started refusing a transfusion
    // the haematologist explicitly permitted.
    showRequestWith(crossmatch({ plateletPairingPendingConfirmation: true }));

    const cell = fixture.nativeElement.querySelector('[data-testid="xm-pending-confirmation-u1"]')
      .parentElement as HTMLElement;
    expect(cell.querySelector('.xm-ok')).not.toBeNull();
    expect(cell.querySelector('.xm-bad')).toBeNull();
  });

  it('shows nothing for pairings the protocol has settled', () => {
    showRequestWith(crossmatch({ plateletPairingPendingConfirmation: false }));

    expect(
      fixture.nativeElement.querySelector('[data-testid="xm-pending-confirmation-u1"]'),
    ).toBeNull();
  });

  it('shows nothing when the server did not send the flag at all', () => {
    // Older responses predate the field; absent must read as "no question",
    // never as a truthy object.
    showRequestWith(crossmatch());

    expect(
      fixture.nativeElement.querySelector('[data-testid="xm-pending-confirmation-u1"]'),
    ).toBeNull();
  });
});

describe('TransfusionComponent: a transfusion in progress (QA CX-008)', () => {
  let fixture: ComponentFixture<TransfusionComponent>;
  let component: TransfusionComponent;
  let txSpy: jasmine.SpyObj<TransfusionService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  const administration = (
    overrides: Partial<TransfusionAdministrationResponse> = {},
  ): TransfusionAdministrationResponse =>
    ({
      id: 'adm-1',
      requestId: 'req-1',
      bloodUnitId: 'u1',
      unitNumber: 'BU-001',
      patientId: 'p1',
      status: 'IN_PROGRESS',
      startedAt: '2026-11-06T10:00:00',
      completedAt: null,
      volumeTransfusedMl: null,
      stopReason: null,
      reactions: [],
      ...overrides,
    }) as TransfusionAdministrationResponse;

  const issuedRequest = mockRequest({
    status: 'ISSUED',
    units: [mockUnit({ id: 'u1', status: 'ISSUED' })],
  });

  const start = async (roles: string[]) => {
    txSpy = jasmine.createSpyObj('TransfusionService', [
      'listRequests',
      'getRequest',
      'listAdministrationsForPatient',
      'completeAdministration',
      'stopAdministration',
      'recordReaction',
      'recordBloodGroup',
    ]);
    txSpy.listRequests.and.returnValue(of([issuedRequest]));
    txSpy.getRequest.and.returnValue(of(issuedRequest));
    txSpy.listAdministrationsForPatient.and.returnValue(of([administration()]));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    await TestBed.configureTestingModule({
      imports: [TransfusionComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: TransfusionService, useValue: txSpy },
        { provide: ToastService, useValue: toastSpy },
        {
          provide: RoleContextService,
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(TransfusionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.openRequest(issuedRequest);
    fixture.detectChanges();
  };

  const el = (testId: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`);

  it('a nurse sees the three actions on the running bag, and hang is gone for that bag', async () => {
    await start(['ROLE_NURSE']);
    expect(el('suspected-reaction')).toBeTruthy();
    expect(el('complete-administration')).toBeTruthy();
    expect(el('stop-administration')).toBeTruthy();
    expect(el('hang-unit')).toBeNull();
    expect(component.hangableUnits()).toEqual([]);
  });

  it('the suspected reaction is the first action, one click from the running bag', async () => {
    await start(['ROLE_NURSE']);
    const actions = (fixture.nativeElement as HTMLElement).querySelector(
      '.administration-actions',
    )!;
    expect(actions.firstElementChild).toBe(el('suspected-reaction'));
    expect(actions.firstElementChild!.classList).toContain('btn-danger');

    el('suspected-reaction')!.click();
    fixture.detectChanges();
    expect(component.showReactionModal()).toBeTrue();
    expect(component.reactionTarget()).toBe('adm-1');
    expect(component.reactionForm.onsetAt).withContext('the time is already there').not.toBe('');
  });

  it('a bag not yet hung still offers hang, once the list has answered', async () => {
    await start(['ROLE_NURSE']);
    txSpy.listAdministrationsForPatient.and.returnValue(of([]));
    component.loadAdministrations();
    fixture.detectChanges();
    expect(el('hang-unit')).toBeTruthy();
    expect(el('suspected-reaction')).toBeNull();
  });

  it('when the list cannot be read, it says so and offers no hang: the bag may be running', async () => {
    await start(['ROLE_NURSE']);
    txSpy.listAdministrationsForPatient.and.returnValue(throwError(() => new Error('down')));
    component.administrations.set([]);
    component.loadAdministrations();
    fixture.detectChanges();
    expect(el('administrations-failed')).toBeTruthy();
    expect(el('hang-unit')).toBeNull();
  });

  it('only administrations of the open request are listed', async () => {
    await start(['ROLE_NURSE']);
    txSpy.listAdministrationsForPatient.and.returnValue(
      of([administration(), administration({ id: 'other', requestId: 'req-2' })]),
    );
    component.loadAdministrations();
    expect(component.administrations().map((a) => a.id)).toEqual(['adm-1']);
  });

  it('an answer for a request the panel has left is dropped', async () => {
    await start(['ROLE_NURSE']);
    const late = new Subject<TransfusionAdministrationResponse[]>();
    txSpy.listAdministrationsForPatient.and.returnValue(late);
    component.loadAdministrations();
    component.closeRequest();
    late.next([administration()]);
    expect(component.administrations()).toEqual([]);
  });

  it('completing asks first, sends the volume, shows the result and refreshes', async () => {
    await start(['ROLE_NURSE']);
    txSpy.completeAdministration.and.returnValue(of(administration({ status: 'COMPLETED' })));
    el('complete-administration')!.click();
    fixture.detectChanges();
    expect(txSpy.completeAdministration).not.toHaveBeenCalled();
    expect(el('confirm-dialog')).toBeTruthy();

    component.completeVolumeMl = 300;
    el('confirm-dialog-confirm')!.click();
    expect(txSpy.completeAdministration).toHaveBeenCalledWith('adm-1', 300);
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.completingAdministration()).toBeNull();
    expect(txSpy.getRequest.calls.count()).toBe(2);
  });

  it('cancelling the confirmation leaves the transfusion running', async () => {
    await start(['ROLE_NURSE']);
    el('complete-administration')!.click();
    fixture.detectChanges();
    el('confirm-dialog-cancel')!.click();
    fixture.detectChanges();
    expect(txSpy.completeAdministration).not.toHaveBeenCalled();
    expect(el('confirm-dialog')).toBeNull();
  });

  it('a refused completion shows the server reason and keeps the dialog', async () => {
    await start(['ROLE_NURSE']);
    txSpy.completeAdministration.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'Déjà terminée' } })),
    );
    component.openComplete(administration());
    component.confirmComplete(administration());
    expect(toastSpy.error).toHaveBeenCalledWith('Déjà terminée');
    expect(component.completingAdministration()).not.toBeNull();
    expect(component.completeBusy()).toBeFalse();
  });

  it('stopping needs a reason in the shared dialog, then sends it', async () => {
    await start(['ROLE_NURSE']);
    txSpy.stopAdministration.and.returnValue(of(administration({ status: 'STOPPED' })));
    el('stop-administration')!.click();
    expect(reasonDialog(fixture)).toBeTruthy();
    expect(txSpy.stopAdministration).not.toHaveBeenCalled();

    answerReasonDialog(fixture, 'Voie veineuse perdue');
    expect(txSpy.stopAdministration).toHaveBeenCalledWith('adm-1', 'Voie veineuse perdue');
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.stoppingAdministration()).toBeNull();
  });

  it('after ending it, the keyboard lands on the section, not on the page body', async () => {
    await start(['ROLE_NURSE']);
    txSpy.stopAdministration.and.returnValue(of(administration({ status: 'STOPPED' })));
    txSpy.listAdministrationsForPatient.and.returnValue(
      of([administration({ status: 'STOPPED' })]),
    );
    component.confirmStop(administration(), 'Voie veineuse perdue');
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(document.activeElement?.id).toBe('tx-administrations-heading');
  });

  it('a finished administration offers only a later reaction, and shows what happened', async () => {
    await start(['ROLE_NURSE']);
    txSpy.listAdministrationsForPatient.and.returnValue(
      of([
        administration({
          status: 'STOPPED',
          stopReason: 'Transfusion reaction: ALLERGIC',
          reactions: [
            {
              id: 'rx1',
              reactionType: 'ALLERGIC',
              severity: 'MILD',
              onsetAt: '2026-11-06T10:30:00',
            },
          ] as TransfusionReactionResponse[],
        }),
      ]),
    );
    component.loadAdministrations();
    fixture.detectChanges();
    expect(el('complete-administration')).toBeNull();
    expect(el('stop-administration')).toBeNull();
    expect(el('suspected-reaction')).toBeNull();
    expect(el('later-reaction')).toBeTruthy();
    expect(el('reaction-rx1')).toBeTruthy();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
      'Transfusion reaction: ALLERGIC',
    );
  });

  it('a laboratory role reads the administration and is offered none of its actions', async () => {
    await start(['ROLE_LAB_TECHNICIAN']);
    expect(el('administration-adm-1')).toBeTruthy();
    expect(el('suspected-reaction')).toBeNull();
    expect(el('complete-administration')).toBeNull();
    expect(el('stop-administration')).toBeNull();
    expect(el('hang-unit')).toBeNull();
  });

  it('hang names the second checker from the list of colleagues, by user', async () => {
    await start(['ROLE_NURSE']);
    const nurseTasks = TestBed.inject(NurseTaskService);
    spyOn(nurseTasks, 'getAdministrationWitnessCandidates').and.returnValue(
      of([
        { userId: 'user-2', displayName: 'Aminata Sawadogo' },
      ] as AdministrationWitnessCandidate[]),
    );
    txSpy.startAdministration = jasmine
      .createSpy()
      .and.returnValue(of(administration())) as typeof txSpy.startAdministration;
    component.openHang(mockUnit({ status: 'ISSUED' }));
    fixture.detectChanges();
    expect(el('hang-verifier')!.textContent).toContain('Aminata Sawadogo');

    component.hangForm.verifiedByUserId = 'user-2';
    component.submitHang();
    expect(txSpy.startAdministration).toHaveBeenCalledWith(
      jasmine.objectContaining({ verifiedByUserId: 'user-2', bloodUnitId: 'u1' }),
    );
  });

  it('hang says so when the list of colleagues cannot be read, and sends nothing', async () => {
    await start(['ROLE_NURSE']);
    spyOn(TestBed.inject(NurseTaskService), 'getAdministrationWitnessCandidates').and.returnValue(
      throwError(() => new Error('down')),
    );
    txSpy.startAdministration = jasmine.createSpy() as typeof txSpy.startAdministration;
    component.openHang(mockUnit({ status: 'ISSUED' }));
    expect(component.verifierCandidatesFailed()).toBeTrue();
    component.submitHang();
    expect(txSpy.startAdministration).not.toHaveBeenCalled();
  });

  it('QA CX-011: the laboratory reaches the screen from the page and from the request', async () => {
    await start(['ROLE_LAB_TECHNICIAN']);
    expect(el('record-group-any-patient')).toBeTruthy();
    el('record-group-for-request')!.click();
    expect(component.groupForm.patientId).toBe('p1');
    expect(component.groupNeedsPatient()).toBeFalse();

    component.openGroupModalForAnyPatient();
    expect(component.groupForm.patientId).toBe('');
    expect(component.groupNeedsPatient()).toBeTrue();
    component.onGroupPatientPicked({ id: 'p9' } as PatientResponse);
    expect(component.groupForm.patientId).toBe('p9');
  });

  it('QA CX-011: a repeat screen refreshes the open request it was taken for', async () => {
    await start(['ROLE_LAB_TECHNICIAN']);
    txSpy.recordBloodGroup.and.returnValue(of({ id: 'g2' } as PatientBloodGroupResponse));
    const fetches = txSpy.getRequest.calls.count();
    component.openGroupModalForRequest(issuedRequest);
    component.submitGroup();
    expect(txSpy.getRequest.calls.count()).toBe(fetches + 1);
  });

  it('QA CX-011: a screen with no patient chosen is refused on the patient field', async () => {
    await start(['ROLE_LAB_TECHNICIAN']);
    component.closeRequest();
    fixture.detectChanges();
    component.openGroupModalForAnyPatient();
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, { button: '[data-testid="submit-group"]' });
    expect(marked).toEqual(['grp-patient']);
    expect(txSpy.recordBloodGroup).not.toHaveBeenCalled();
  });

  it('a clinician who cannot do lab work is not offered the screen', async () => {
    await start(['ROLE_NURSE']);
    expect(el('record-group-any-patient')).toBeNull();
    expect(el('record-group-for-request')).toBeNull();
  });
});
