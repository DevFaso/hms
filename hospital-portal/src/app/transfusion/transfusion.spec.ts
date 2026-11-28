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
  EmergencyReleaseOptions,
  OPositiveReleaseDecision,
  OPositiveRule,
  RhFactor,
} from '../services/transfusion.service';
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
      'listVerifierCandidates',
      'getAntibodyStatus',
    ]);
    // QA D3351: the request form also asks what is known of the patient's antibodies (nothing here).
    txSpy.getAntibodyStatus.and.returnValue(
      of({
        patientId: 'p1',
        alloImmunised: false,
        positiveScreenAt: null,
        identified: false,
        antibodySpecificities: null,
        workups: [],
      }),
    );
    txSpy.listAdministrationsForPatient.and.returnValue(of([]));
    txSpy.listVerifierCandidates.and.returnValue(of([]));
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

  it('leaves the group empty when the patient has never been typed (QA D3352: 204, no body)', () => {
    txSpy.getCurrentBloodGroup.and.returnValue(of(null));

    component.onPatientPicked({ id: 'p1' } as PatientResponse);

    expect(component.patientGroup()).toBeNull();
  });

  it('never fills the form of the patient picked since with a late answer for the previous one (#1300 review)', () => {
    const lateGroupA = new Subject<PatientBloodGroupResponse | null>();
    const groupB = {
      aboGroup: 'O',
      rhFactor: 'NEGATIVE',
      screenCurrent: true,
    } as PatientBloodGroupResponse;
    txSpy.getCurrentBloodGroup.and.callFake((id: string) =>
      id === 'pA' ? lateGroupA.asObservable() : of(groupB),
    );

    component.onPatientPicked({ id: 'pA' } as PatientResponse);
    component.onPatientPicked({ id: 'pB' } as PatientResponse);
    expect(component.patientGroup()).toBe(groupB);

    // A's answer arrives after B was picked: dropped, whether a group, no group (204) or a failure.
    lateGroupA.next({
      aboGroup: 'AB',
      rhFactor: 'POSITIVE',
      screenCurrent: true,
    } as PatientBloodGroupResponse);
    expect(component.patientGroup()).toBe(groupB);
    lateGroupA.next(null);
    expect(component.patientGroup()).toBe(groupB);
    lateGroupA.error(new Error('500'));
    expect(component.patientGroup()).toBe(groupB);
  });

  it('leaves the group empty when the read fails', () => {
    txSpy.getCurrentBloodGroup.and.returnValue(throwError(() => new Error('500')));

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
      'listVerifierCandidates',
      'getAntibodyStatus',
    ]);
    // QA D3351: the request form also asks what is known of the patient's antibodies (nothing here).
    txSpy.getAntibodyStatus.and.returnValue(
      of({
        patientId: 'p1',
        alloImmunised: false,
        positiveScreenAt: null,
        identified: false,
        antibodySpecificities: null,
        workups: [],
      }),
    );
    txSpy.listAdministrationsForPatient.and.returnValue(of([]));
    txSpy.listVerifierCandidates.and.returnValue(of([]));
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
      'listVerifierCandidates',
      'listAssignableUnits',
      'issueUnit',
      'startAdministration',
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

  const reopenWith = (list: TransfusionAdministrationResponse[]) => {
    txSpy.listAdministrationsForPatient.and.returnValue(of(list));
    component.openRequest(issuedRequest);
    fixture.detectChanges();
  };

  it('a running bag found incompatible says so on the request and on its card, and is still running', async () => {
    await start(['ROLE_NURSE']);
    reopenWith([
      administration({
        crossmatch: {
          compatible: false,
          incompatibilityReason: 'Anti-K',
        } as TransfusionAdministrationResponse['crossmatch'],
      }),
    ]);
    expect(component.runningIncompatible().length).toBe(1);
    expect(el('running-incompatible-banner')).toBeTruthy();
    expect(el('xm-incompatible-adm-1')).toBeTruthy();
    expect(el('stop-administration')).withContext('the clinician decides').toBeTruthy();
  });

  it('a running bag with a compatible result shows no alert; a finished one keeps the fact, not the banner', async () => {
    await start(['ROLE_NURSE']);
    reopenWith([
      administration({
        crossmatch: { compatible: true } as TransfusionAdministrationResponse['crossmatch'],
      }),
    ]);
    expect(el('running-incompatible-banner')).toBeNull();
    expect(el('xm-incompatible-adm-1')).toBeNull();
    reopenWith([
      administration({
        status: 'COMPLETED',
        crossmatch: { compatible: false } as TransfusionAdministrationResponse['crossmatch'],
      }),
    ]);
    expect(el('running-incompatible-banner')).toBeNull();
    expect(el('xm-incompatible-adm-1')).toBeTruthy();
  });

  it('the lab can pick the running bag: the result still has to reach the clinician', async () => {
    await start(['ROLE_NURSE']);
    txSpy.listAssignableUnits.and.returnValue(of([]));
    component.openCrossmatch();
    expect(component.assignableUnits().map((u) => u.id)).toContain('u1');
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
    await start(['ROLE_SURGEON']);
    txSpy.listVerifierCandidates = jasmine
      .createSpy()
      .and.returnValue(of([{ userId: 'user-2', displayName: 'Aminata Sawadogo' }]));
    txSpy.startAdministration = jasmine
      .createSpy()
      .and.returnValue(of(administration())) as typeof txSpy.startAdministration;
    component.openHang(mockUnit({ status: 'ISSUED' }));
    fixture.detectChanges();
    expect(el('hang-verifier')!.textContent).toContain('Aminata Sawadogo');

    component.hangForm.verifiedByUserId = 'user-2';
    // QA D4141: the colleague named confirms with their own factor (transfusion-r81.spec.ts).
    component.hangForm.verifierSecret = 'Her-Own-Password-1';
    component.submitHang();
    expect(txSpy.startAdministration).toHaveBeenCalledWith(
      jasmine.objectContaining({ verifiedByUserId: 'user-2', bloodUnitId: 'u1' }),
    );
  });

  it('hang says so when the list of colleagues cannot be read, and sends nothing', async () => {
    await start(['ROLE_NURSE']);
    txSpy.listVerifierCandidates = jasmine
      .createSpy()
      .and.returnValue(throwError(() => new Error('down')));
    txSpy.startAdministration = jasmine.createSpy() as typeof txSpy.startAdministration;
    component.openHang(mockUnit({ status: 'ISSUED' }));
    expect(component.verifierCandidatesFailed()).toBeTrue();
    component.submitHang();
    expect(txSpy.startAdministration).not.toHaveBeenCalled();
  });

  it('QA CX-011: the crossmatch list offers the bags this request holds, first, a running one included', async () => {
    await start(['ROLE_LAB_TECHNICIAN']);
    txSpy.listAssignableUnits = jasmine
      .createSpy()
      .and.returnValue(of([mockUnit({ id: 'stock', status: 'AVAILABLE' })]));
    component.selectedRequest.set(
      mockRequest({
        units: [
          mockUnit({ id: 'held', status: 'CROSSMATCHED' }),
          mockUnit({ id: 'out', status: 'ISSUED' }),
          mockUnit({ id: 'u1', status: 'ISSUED' }),
        ],
      }),
    );
    component.openCrossmatch();
    expect(component.assignableUnits().map((u) => u.id)).toEqual(['held', 'out', 'u1', 'stock']);
  });

  describe('emergency release', () => {
    const emergency = mockRequest({
      id: 'req-1',
      urgency: 'EMERGENCY',
      status: 'REQUESTED',
      productType: 'PACKED_RED_CELLS',
      units: [],
    });

    const open = async (roles: string[]) => {
      await start(roles);
      txSpy.getRequest.and.returnValue(of(emergency));
      component.openRequest(emergency);
      fixture.detectChanges();
    };

    const options = (over: Partial<EmergencyReleaseOptions> = {}): EmergencyReleaseOptions => ({
      antibodyWarning: false,
      antibodyDetail: null,
      negativeStock: [],
      units: [],
      incompatibleUnits: [],
      deciders: [{ userId: 'doc', displayName: 'Dr A' }],
      authorisers: [{ userId: 'dir', displayName: 'Dir B' }],
      ...over,
    });
    const unitOption = (id: string, decisionRequired = false) => ({
      id,
      unitNumber: id,
      aboGroup: 'O' as const,
      rhFactor: (decisionRequired ? 'POSITIVE' : 'NEGATIVE') as RhFactor,
      expiresOn: '2030-01-01',
      decisionRequired,
    });
    const stubOptions = (over: Partial<EmergencyReleaseOptions> = {}) => {
      txSpy.emergencyReleaseOptions = jasmine.createSpy().and.returnValue(of(options(over)));
    };

    describe('V380: the O positive authority rule', () => {
      const rule = (over: Partial<OPositiveRule> = {}): OPositiveRule => ({
        riskTier: 'HIGH_RISK',
        tierReason: 'FEMALE_AT_OR_BELOW_CUTOFF',
        protocolActive: true,
        femaleAgeCutoff: 50,
        boysLowRisk: false,
        reviewDeadlineHours: 24,
        issuerMayDecide: false,
        plainIssueAllowed: false,
        physicians: [{ userId: 'phys', displayName: 'Dr Physician' }],
        biologists: [],
        ...over,
      });
      const openWith = async (positiveRule: OPositiveRule) => {
        await open(['ROLE_LAB_TECHNICIAN']);
        stubOptions({ units: [unitOption('opos', true)], positiveRule });
        txSpy.emergencyRelease = jasmine
          .createSpy()
          .and.returnValue(of(mockUnit({ id: 'opos', status: 'ISSUED' })));
        component.openEmergencyRelease(emergency);
        component.emergencyUnitId = 'opos';
        fixture.detectChanges();
      };

      it('a patient at risk: the form says who decides, offers physicians only, and sends the verbal order', async () => {
        await openWith(rule());
        expect(el('opos-form-tier')).not.toBeNull();
        expect(component.emergencyDeciders().map((c) => c.userId)).toEqual(['phys']);
        expect(el('physician-unreachable')).not.toBeNull();
        expect(el('verbal-order')).not.toBeNull();

        component.emergencyReason = 'O négatif épuisé';
        expect(component.emergencyFormIncomplete()).toBeTrue();
        component.emergencyAuthoriserId = 'phys';
        expect(component.emergencyFormIncomplete()).toBeFalse();
        component.submitEmergencyRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'opos',
          releaseType: 'O_POSITIVE_DECISION',
          reason: 'O négatif épuisé',
          authoriserUserId: 'phys',
          verbalOrder: true,
        });
      });

      it('never blocks: with no physician reachable the issuer submits alone, and says so', async () => {
        await openWith(rule());
        component.emergencyReason = 'O négatif épuisé';
        component.emergencyAuthoriserId = 'phys';
        component.emergencyUnreachable = true;
        component.onEmergencyUnreachableChanged();
        fixture.detectChanges();
        expect(component.emergencyAuthoriserId).toBe('');
        expect(component.emergencyFormIncomplete()).toBeFalse();
        expect(el('verbal-order')).toBeNull();

        component.submitEmergencyRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'opos',
          releaseType: 'O_POSITIVE_DECISION',
          reason: 'O négatif épuisé',
          physicianUnreachable: true,
        });
      });

      it('a known antibody: the biologist named goes with the physician', async () => {
        await openWith(
          rule({
            riskTier: 'KNOWN_ANTIBODY',
            tierReason: 'KNOWN_ANTIBODY',
            biologists: [{ userId: 'bio', displayName: 'Dr Bio' }],
          }),
        );
        expect(document.getElementById('em-biologist')).not.toBeNull();
        component.emergencyReason = 'Hémorragie';
        component.emergencyAuthoriserId = 'phys';
        component.emergencyBiologistId = 'bio';
        component.emergencyVerbalOrder = false;
        component.submitEmergencyRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'opos',
          releaseType: 'O_POSITIVE_DECISION',
          reason: 'Hémorragie',
          authoriserUserId: 'phys',
          verbalOrder: false,
          biologistUserId: 'bio',
        });
      });

      it('the low-risk tier keeps the form as it was: the issuer may be named, nothing more is sent', async () => {
        await openWith(
          rule({
            riskTier: 'LOW_RISK',
            tierReason: 'ADULT_MALE',
            issuerMayDecide: true,
            physicians: [],
          }),
        );
        expect(component.emergencyPhysicianTier()).toBeFalse();
        expect(el('physician-unreachable')).toBeNull();
        expect(component.emergencyDeciders().map((c) => c.userId)).toEqual(['doc']);
        component.emergencyReason = 'Hémorragie';
        component.emergencyAuthoriserId = 'doc';
        component.submitEmergencyRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'opos',
          releaseType: 'O_POSITIVE_DECISION',
          reason: 'Hémorragie',
          authoriserUserId: 'doc',
        });
      });

      it('an incompatible O positive bag for a patient at risk records how the order was given, and the biologist', async () => {
        await open(['ROLE_LAB_TECHNICIAN']);
        stubOptions({
          incompatibleUnits: [
            {
              id: 'opos',
              unitNumber: 'OPOS',
              aboGroup: 'O',
              rhFactor: 'POSITIVE',
              incompatibilityReason: 'Anti-K',
              releasable: true,
            },
            {
              id: 'apos',
              unitNumber: 'APOS',
              aboGroup: 'A',
              rhFactor: 'POSITIVE',
              incompatibilityReason: 'Anti-K',
              releasable: true,
            },
          ],
          positiveRule: rule({
            riskTier: 'KNOWN_ANTIBODY',
            tierReason: 'KNOWN_ANTIBODY',
            biologists: [{ userId: 'bio', displayName: 'Dr Bio' }],
            physicians: [{ userId: 'phys', displayName: 'Dr Physician' }],
          }),
        });
        txSpy.emergencyRelease = jasmine
          .createSpy()
          .and.returnValue(of(mockUnit({ id: 'opos', status: 'ISSUED' })));
        component.openIncompatibleRelease(emergency);

        // A bag of another group is not an O positive release: nothing more is asked.
        component.emergencyUnitId = 'apos';
        fixture.detectChanges();
        expect(component.incompatiblePositiveTier()).toBeNull();
        expect(el('si-opos-tier')).toBeNull();

        component.emergencyUnitId = 'opos';
        fixture.detectChanges();
        expect(el('si-opos-tier')).not.toBeNull();
        expect(el('si-verbal-order')).not.toBeNull();
        expect(document.getElementById('si-biologist')).not.toBeNull();

        component.emergencyAuthoriserId = 'dir';
        component.emergencyReason = 'Rien à temps';
        // V381: every incompatible release says why this bag and whether the patient was told.
        component.incompatibleChoiceReason = 'Seule poche testée';
        component.incompatiblePatientInformed = 'NOT_POSSIBLE';
        component.emergencyVerbalOrder = false;
        component.emergencyBiologistId = 'bio';
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'opos',
          releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
          reason: 'Rien à temps',
          unitChoiceReason: 'Seule poche testée',
          patientInformation: 'NOT_POSSIBLE',
          authoriserUserId: 'dir',
          verbalOrder: false,
          biologistUserId: 'bio',
        });

        // pr1346: who authorises and who ordered O positive are two names, each sent in its own field.
        expect(el('si-ordering')).not.toBeNull();
        component.emergencyOrderingId = 'phys';
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'opos',
          releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
          reason: 'Rien à temps',
          unitChoiceReason: 'Seule poche testée',
          patientInformation: 'NOT_POSSIBLE',
          authoriserUserId: 'dir',
          verbalOrder: false,
          biologistUserId: 'bio',
          orderingPhysicianUserId: 'phys',
        });
        // The same person named twice is one name.
        component.emergencyOrderingId = 'dir';
        component.submitIncompatibleRelease();
        expect(
          txSpy.emergencyRelease.calls.mostRecent().args[1].orderingPhysicianUserId,
        ).toBeUndefined();
      });

      it('a bag still out is shown as issued, administration not confirmed; once given, as an exposure', async () => {
        await start(['ROLE_DOCTOR']);
        const out = mockRequest({
          id: 'req-1',
          urgency: 'EMERGENCY',
          status: 'ISSUED',
          rhdPositiveExposed: true,
          rhdPositiveExposureConfirmed: false,
          rhdPositiveExposedAt: '2026-11-25T22:00:00',
          rhdPositiveExposedHospitalName: 'Clinique A',
        });
        txSpy.getRequest.and.returnValue(of(out));
        component.openRequest(out);
        fixture.detectChanges();
        expect(el('rhd-exposed')!.textContent).toContain(
          'TRANSFUSION.OPOS.EXPOSED_BANNER_UNCONFIRMED',
        );

        const given = { ...out, rhdPositiveExposureConfirmed: true };
        txSpy.getRequest.and.returnValue(of(given));
        component.openRequest(given);
        fixture.detectChanges();
        expect(el('rhd-exposed')!.textContent).toContain('TRANSFUSION.OPOS.EXPOSED_BANNER');
        expect(el('rhd-exposed')!.textContent).not.toContain('UNCONFIRMED');
      });

      it('the request shows the permanent RhD exposure flag and what is owed on each O positive release', async () => {
        await start(['ROLE_DOCTOR']);
        const exposed = mockRequest({
          id: 'req-1',
          urgency: 'EMERGENCY',
          status: 'ISSUED',
          rhdPositiveExposed: true,
          rhdPositiveExposureConfirmed: true,
          rhdPositiveExposedAt: '2026-11-25T22:00:00',
          rhdPositiveExposedHospitalName: 'Clinique A',
          emergencyReleases: [
            {
              id: 'rel-1',
              bloodUnitId: 'opos',
              unitNumber: 'U-OPOS',
              releaseType: 'O_POSITIVE_DECISION',
              reason: 'Hémorragie',
              authoriserName: 'Dr Physician',
              issuedByName: 'Tech',
              issuedAt: '2026-11-25T22:00:00',
              antibodyWarning: false,
              positiveDecision: {
                riskTier: 'HIGH_RISK',
                decisionBasis: 'PHYSICIAN_ORDER',
                countersignDueAt: '2026-11-26T22:00:00',
                countersignedAt: null,
                reviewedAt: null,
              } as OPositiveReleaseDecision,
            },
          ],
        });
        txSpy.getRequest.and.returnValue(of(exposed));
        component.openRequest(exposed);
        fixture.detectChanges();
        expect(el('rhd-exposed')).not.toBeNull();
        const line = el('release-opos-decision')!.textContent ?? '';
        expect(line).toContain('TRANSFUSION.OPOS.TIER_HIGH_RISK');
        expect(line).toContain('TRANSFUSION.OPOS.COUNTERSIGN_OWED');
        expect(line).toContain('TRANSFUSION.OPOS.REVIEW_OWED');
      });
    });

    it('the lab can release from an emergency request with no bag on it, on what the server lists', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({ units: [unitOption('oneg')] });
      txSpy.issueUnit = jasmine
        .createSpy()
        .and.returnValue(of(mockUnit({ id: 'oneg', status: 'ISSUED' })));
      el('emergency-release')!.click();
      expect(component.emergencyOptions()!.units.map((u) => u.id)).toEqual(['oneg']);

      component.emergencyUnitId = 'oneg';
      component.submitEmergencyRelease();
      expect(txSpy.issueUnit).toHaveBeenCalledWith('req-1', 'oneg');
      expect(toastSpy.success).toHaveBeenCalled();
      expect(component.showEmergencyModal()).toBeFalse();
    });

    it('releasing with no bag chosen is refused on the field', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions();
      el('emergency-release')!.click();
      fixture.detectChanges();
      const marked = expectEmptySubmitRefusal(fixture, {
        button: '[data-testid="submit-emergency-release"]',
      });
      expect(marked).toEqual(['em-unit']);
    });

    it('a refusal shows the server reason and keeps the form', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({ units: [unitOption('x')] });
      txSpy.issueUnit = jasmine
        .createSpy()
        .and.returnValue(throwError(() => ({ status: 400, error: { message: 'Déjà réservée' } })));
      component.openEmergencyRelease(emergency);
      component.emergencyUnitId = 'x';
      component.submitEmergencyRelease();
      expect(toastSpy.error).toHaveBeenCalledWith('Déjà réservée');
      expect(component.showEmergencyModal()).toBeTrue();
    });

    it('a bag the server says needs a decision asks for a reason and a name, then sends them', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({ units: [unitOption('opos', true)] });
      txSpy.emergencyRelease = jasmine
        .createSpy()
        .and.returnValue(of(mockUnit({ id: 'opos', status: 'ISSUED' })));
      txSpy.issueUnit = jasmine.createSpy();
      component.openEmergencyRelease(emergency);
      component.emergencyUnitId = 'opos';
      fixture.detectChanges();
      expect(el('decision-required')).not.toBeNull();

      component.submitEmergencyRelease();
      expect(txSpy.emergencyRelease).not.toHaveBeenCalled();

      component.emergencyAuthoriserId = 'doc';
      component.emergencyReason = '  Hémorragie  ';
      component.submitEmergencyRelease();
      expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
        bloodUnitId: 'opos',
        releaseType: 'O_POSITIVE_DECISION',
        reason: 'Hémorragie',
        authoriserUserId: 'doc',
      });
      expect(txSpy.issueUnit).not.toHaveBeenCalled();
    });

    it('O negative showing in stock: O positive needs the statement, and it is sent', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({
        negativeStock: [unitOption('ONEG-1')],
        units: [{ ...unitOption('opos', true), statementRequired: true }, unitOption('ONEG-1')],
      });
      txSpy.emergencyRelease = jasmine
        .createSpy()
        .and.returnValue(of(mockUnit({ id: 'opos', status: 'ISSUED' })));
      component.openEmergencyRelease(emergency);
      component.emergencyUnitId = 'opos';
      component.emergencyAuthoriserId = 'doc';
      component.emergencyReason = 'Hémorragie';
      fixture.detectChanges();
      expect(el('o-negative-first')).not.toBeNull();
      expect(component.negativeStockNumbers()).toBe('ONEG-1');
      expect(el('negative-statement')).not.toBeNull();

      component.submitEmergencyRelease();
      expect(txSpy.emergencyRelease).not.toHaveBeenCalled();

      component.emergencyNegativeStated = true;
      component.submitEmergencyRelease();
      expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
        bloodUnitId: 'opos',
        releaseType: 'O_POSITIVE_DECISION',
        reason: 'Hémorragie',
        authoriserUserId: 'doc',
        negativeUnobtainable: true,
      });
    });

    it('no O negative showing: no statement is asked or sent', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({ units: [unitOption('opos', true)] });
      component.openEmergencyRelease(emergency);
      component.emergencyUnitId = 'opos';
      component.emergencyAuthoriserId = 'doc';
      component.emergencyReason = 'Hémorragie';
      txSpy.emergencyRelease = jasmine
        .createSpy()
        .and.returnValue(of(mockUnit({ id: 'opos', status: 'ISSUED' })));
      fixture.detectChanges();
      expect(el('negative-statement')).toBeNull();
      component.submitEmergencyRelease();
      expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
        bloodUnitId: 'opos',
        releaseType: 'O_POSITIVE_DECISION',
        reason: 'Hémorragie',
        authoriserUserId: 'doc',
      });
    });

    it('a statement is made for one bag and one list: another bag or another list asks again', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({
        negativeStock: [unitOption('ONEG-1')],
        units: [{ ...unitOption('opos', true), statementRequired: true }],
      });
      txSpy.emergencyRelease = jasmine
        .createSpy()
        .and.returnValue(throwError(() => ({ status: 400, error: { message: 'Non' } })));
      component.openEmergencyRelease(emergency);
      component.emergencyUnitId = 'opos';
      component.emergencyNegativeStated = true;
      component.onEmergencyUnitPicked();
      expect(component.emergencyNegativeStated).toBeFalse();

      component.emergencyNegativeStated = true;
      component.emergencyAuthoriserId = 'doc';
      component.emergencyReason = 'Hémorragie';
      stubOptions({
        negativeStock: [unitOption('ONEG-1'), unitOption('ONEG-2')],
        units: [{ ...unitOption('opos', true), statementRequired: true }],
      });
      component.submitEmergencyRelease();
      expect(component.emergencyNegativeStated).toBeFalse();
      expect(component.negativeStockNumbers()).toBe('ONEG-1, ONEG-2');
    });

    it('the incompatible form asks for the statement on the bag that needs it, and sends it', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({
        negativeStock: [unitOption('ONEG-1')],
        incompatibleUnits: [
          {
            id: 'opos',
            unitNumber: 'OPOS',
            aboGroup: 'O',
            rhFactor: 'POSITIVE',
            incompatibilityReason: 'Anti-K',
            releasable: true,
            statementRequired: true,
          },
        ],
      });
      txSpy.emergencyRelease = jasmine
        .createSpy()
        .and.returnValue(of(mockUnit({ id: 'opos', status: 'ISSUED' })));
      component.openIncompatibleRelease(emergency);
      component.emergencyUnitId = 'opos';
      component.emergencyAuthoriserId = 'dir';
      component.emergencyReason = 'Rien à temps';
      component.incompatibleChoiceReason = 'Seule poche testée';
      component.incompatiblePatientInformed = 'FAMILY_INFORMED';
      fixture.detectChanges();
      expect(el('si-o-negative-first')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('#si-statement')).not.toBeNull();

      component.submitIncompatibleRelease();
      expect(txSpy.emergencyRelease).not.toHaveBeenCalled();

      component.emergencyNegativeStated = true;
      component.submitIncompatibleRelease();
      expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
        bloodUnitId: 'opos',
        releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
        reason: 'Rien à temps',
        unitChoiceReason: 'Seule poche testée',
        patientInformation: 'FAMILY_INFORMED',
        authoriserUserId: 'dir',
        negativeUnobtainable: true,
      });
    });

    it('says O negative goes first and warns of a positive screen, without blocking the form', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      stubOptions({
        negativeStock: [unitOption('oneg')],
        antibodyWarning: true,
        antibodyDetail: 'Anti-D',
        units: [unitOption('oneg')],
      });
      component.openEmergencyRelease(emergency);
      fixture.detectChanges();
      expect(el('o-negative-first')).not.toBeNull();
      expect(el('antibody-warning')!.textContent).toContain('Anti-D');
    });

    describe('serologically incompatible', () => {
      const withResult = mockRequest({
        id: 'req-1',
        urgency: 'EMERGENCY',
        status: 'REQUESTED',
        productType: 'PACKED_RED_CELLS',
        units: [],
        crossmatches: [
          {
            id: 'x',
            requestId: 'req-1',
            bloodUnitId: 'bag',
            unitNumber: 'BAG',
            compatible: false,
            method: null,
            incompatibilityReason: 'Anti-K',
            performedByName: null,
            performedAt: '2026-11-06T10:00:00',
            expiresAt: null,
            usable: false,
          },
        ],
      });

      const openWithResult = async (roles: string[]) => {
        await start(roles);
        txSpy.getRequest.and.returnValue(of(withResult));
        component.openRequest(withResult);
        fixture.detectChanges();
      };

      describe('QA D1451: every box of the #1056 flow answers a real click', () => {
        const root = () => fixture.nativeElement as HTMLElement;
        const settle = async () => {
          fixture.detectChanges();
          await fixture.whenStable();
          fixture.detectChanges();
        };
        const pick = async (selector: string, value: string) => {
          const select = root().querySelector<HTMLSelectElement>(selector)!;
          select.value = value;
          select.dispatchEvent(new Event('change'));
          await settle();
        };
        const type = async (selector: string, value: string) => {
          const field = root().querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
          field.value = value;
          field.dispatchEvent(new Event('input'));
          await settle();
        };
        const box = (selector: string) => root().querySelector<HTMLInputElement>(selector)!;
        /** The box itself (a Space key activates it the same way), then its label. */
        const expectToggles = async (selector: string) => {
          const before = box(selector).checked;
          box(selector).click();
          await settle();
          expect(box(selector).checked).withContext('a click on the box').toBe(!before);
          box(selector).closest('label')!.click();
          await settle();
          expect(box(selector).checked).withContext('a click on its label').toBe(before);
          box(selector).click();
          await settle();
          expect(box(selector).checked).withContext('and back').toBe(!before);
        };
        const buttonOf = (key: string) =>
          [...root().querySelectorAll('button')].find((b) => b.textContent!.includes(key))!;

        it('the crossmatch box can record an incompatible result, and that is what is sent', async () => {
          await open(['ROLE_LAB_DIRECTOR']);
          txSpy.listAssignableUnits.and.returnValue(
            of([mockUnit({ id: 'bag', status: 'AVAILABLE' })]),
          );
          txSpy.recordCrossmatch = jasmine
            .createSpy()
            .and.returnValue(of({ id: 'x1' } as CrossmatchResponse));
          buttonOf('TRANSFUSION.RECORD_CROSSMATCH').click();
          await settle();
          await pick('#xm-unit', 'bag');
          const compatible = '.modal-body input[type="checkbox"]';
          expect(box(compatible).checked).toBeTrue();
          expect(root().querySelector('#xm-reason')).toBeNull();

          box(compatible).click();
          await settle();
          expect(box(compatible).checked).toBeFalse();
          await type('#xm-reason', 'Anti-K');
          root().querySelector<HTMLButtonElement>('.modal-actions .btn-primary')!.click();
          expect(txSpy.recordCrossmatch).toHaveBeenCalledWith('req-1', {
            bloodUnitId: 'bag',
            compatible: false,
            method: undefined,
            incompatibilityReason: 'Anti-K',
          });
        });

        it('the O-negative statement can be ticked, and the release then goes out', async () => {
          await open(['ROLE_LAB_DIRECTOR']);
          stubOptions({
            negativeStock: [unitOption('ONEG-1')],
            units: [{ ...unitOption('opos', true), statementRequired: true }, unitOption('ONEG-1')],
          });
          txSpy.emergencyRelease = jasmine
            .createSpy()
            .and.returnValue(of(mockUnit({ id: 'opos', status: 'ISSUED' })));
          el('emergency-release')!.click();
          await settle();
          await pick('#em-unit', 'opos');
          await pick('#em-decider', 'doc');
          await type('#em-reason', 'Hémorragie');
          await expectToggles('#em-statement');
          expect(component.emergencyNegativeStated).toBeTrue();

          el('submit-emergency-release')!.click();
          expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
            bloodUnitId: 'opos',
            releaseType: 'O_POSITIVE_DECISION',
            reason: 'Hémorragie',
            authoriserUserId: 'doc',
            negativeUnobtainable: true,
          });
        });

        it('the statement of the incompatible form can be ticked, and the release then goes out', async () => {
          await openWithResult(['ROLE_LAB_DIRECTOR']);
          stubOptions({
            negativeStock: [unitOption('ONEG-1')],
            incompatibleUnits: [
              {
                id: 'bag',
                unitNumber: 'BAG',
                aboGroup: 'O',
                rhFactor: 'POSITIVE',
                incompatibilityReason: 'Anti-K',
                releasable: true,
                statementRequired: true,
              },
            ],
          });
          txSpy.emergencyRelease = jasmine
            .createSpy()
            .and.returnValue(of(mockUnit({ id: 'bag', status: 'ISSUED' })));
          el('incompatible-release')!.click();
          await settle();
          await pick('#si-unit', 'bag');
          await pick('#si-authoriser', 'dir');
          await type('#si-reason', 'Rien de compatible à temps');
          await type('#si-choice', 'Seule poche testée');
          await pick('#si-informed', 'PATIENT_INFORMED');
          await expectToggles('#si-statement');

          el('submit-incompatible-release')!.click();
          expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
            bloodUnitId: 'bag',
            releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
            reason: 'Rien de compatible à temps',
            unitChoiceReason: 'Seule poche testée',
            patientInformation: 'PATIENT_INFORMED',
            authoriserUserId: 'dir',
            negativeUnobtainable: true,
          });
        });

        it('"Unit returned" can be ticked on a reaction', async () => {
          await open(['ROLE_NURSE']);
          component.openReaction('adm-1');
          await settle();
          const before = component.reactionForm.unitReturnedToLab;
          await expectToggles('.modal-body input[type="checkbox"]');
          expect(component.reactionForm.unitReturnedToLab).toBe(!before);
        });

        it('a click inside the dialog leaves it open and is not cancelled; one on the backdrop closes it', async () => {
          await open(['ROLE_LAB_DIRECTOR']);
          txSpy.listAssignableUnits.and.returnValue(of([]));
          component.openCrossmatch();
          await settle();
          const inside = new MouseEvent('click', { bubbles: true, cancelable: true });
          root().querySelector('.modal-body')!.dispatchEvent(inside);
          await settle();
          expect(inside.defaultPrevented).toBeFalse();
          expect(component.showCrossmatchModal()).toBeTrue();

          root().querySelector<HTMLElement>('.modal-backdrop')!.click();
          await settle();
          expect(component.showCrossmatchModal()).toBeFalse();
        });
      });

      it('is its own action, offered only with an incompatible result on the request', async () => {
        await open(['ROLE_LAB_TECHNICIAN']);
        expect(el('incompatible-release')).toBeNull();
        expect(component.canReleaseIncompatible(withResult)).toBeTrue();
        expect(component.canReleaseIncompatible({ ...withResult, urgency: 'URGENT' })).toBeFalse();
      });

      it('needs a bag, an authoriser and a reason, and sends exactly that type', async () => {
        await openWithResult(['ROLE_LAB_TECHNICIAN']);
        stubOptions({
          incompatibleUnits: [
            {
              id: 'bag',
              unitNumber: 'BAG',
              aboGroup: 'A',
              rhFactor: 'POSITIVE',
              incompatibilityReason: 'Anti-K',
              releasable: true,
            },
            {
              id: 'no',
              unitNumber: 'NO',
              aboGroup: 'B',
              rhFactor: 'POSITIVE',
              incompatibilityReason: 'Anti-K',
              releasable: false,
            },
          ],
        });
        txSpy.emergencyRelease = jasmine
          .createSpy()
          .and.returnValue(of(mockUnit({ id: 'bag', status: 'ISSUED' })));
        el('incompatible-release')!.click();
        fixture.detectChanges();
        const refused = fixture.nativeElement.querySelector('#si-unit option[disabled]');
        expect(refused).not.toBeNull();
        expect(component.emergencyOptions()!.authorisers.map((c) => c.userId)).toEqual(['dir']);

        component.emergencyUnitId = 'bag';
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).not.toHaveBeenCalled();

        component.emergencyAuthoriserId = 'dir';
        component.emergencyReason = 'Rien de compatible à temps';
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).not.toHaveBeenCalled();

        // V381: every incompatible release says why this bag and whether the patient was told.
        component.incompatibleChoiceReason = '  Seule poche compatible ABO testée  ';
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).not.toHaveBeenCalled();
        component.incompatiblePatientInformed = 'NOT_POSSIBLE';
        // With the hospital's fallback off, nothing offers to release without a physician.
        fixture.detectChanges();
        expect(el('si-unreachable')).toBeNull();
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'bag',
          releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
          reason: 'Rien de compatible à temps',
          unitChoiceReason: 'Seule poche compatible ABO testée',
          patientInformation: 'NOT_POSSIBLE',
          authoriserUserId: 'dir',
        });
        expect(component.showIncompatibleModal()).toBeFalse();
      });

      it('V381: where the hospital enabled it, « aucun médecin joignable » replaces the name and sends its reason', async () => {
        await openWithResult(['ROLE_LAB_TECHNICIAN']);
        const bag = (id: string, leastIncompatible: boolean) => ({
          id,
          unitNumber: id.toUpperCase(),
          aboGroup: 'A' as const,
          rhFactor: 'POSITIVE' as RhFactor,
          incompatibilityReason: 'Anti-K',
          releasable: true,
          rhdMatched: true,
          rhKellMatched: leastIncompatible,
          antigenNegative: null,
          leastIncompatible,
        });
        stubOptions({
          incompatibleUnits: [bag('bag', true), bag('worse', false)],
          incompatibleRule: {
            fallbackEnabled: true,
            callerMayDeclare: true,
            countersignDeadlineHours: 6,
          },
        });
        txSpy.emergencyRelease = jasmine
          .createSpy()
          .and.returnValue(of(mockUnit({ id: 'bag', status: 'ISSUED' })));
        el('incompatible-release')!.click();
        fixture.detectChanges();
        expect(el('si-unreachable')).not.toBeNull();
        expect(el('si-fallback-off')).toBeNull();
        expect(document.getElementById('si-authoriser')).not.toBeNull();

        component.emergencyUnitId = 'worse';
        component.emergencyAuthoriserId = 'dir';
        component.emergencyReason = 'Hémorragie massive';
        component.incompatibleChoiceReason = 'Seule poche testée';
        component.incompatiblePatientInformed = 'NOT_POSSIBLE';
        component.incompatibleUnreachable = true;
        component.onIncompatibleUnreachableChanged();
        fixture.detectChanges();
        // The declaration stands in for the name: nobody is named, and nobody is waited for.
        expect(component.emergencyAuthoriserId).toBe('');
        expect(document.getElementById('si-authoriser')).toBeNull();
        expect(el('si-fallback-hint')!.textContent).toContain('TRANSFUSION.INCOMPAT.FALLBACK_HINT');
        expect(el('si-not-least')).not.toBeNull();
        expect(el('si-unit-facts')).not.toBeNull();

        component.emergencyUnitId = 'bag';
        fixture.detectChanges();
        expect(el('si-not-least')).toBeNull();
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).not.toHaveBeenCalled();

        component.incompatibleUnreachableReason = ' Dr de garde appelé à 01 h 40, sans réponse ';
        component.submitIncompatibleRelease();
        expect(txSpy.emergencyRelease).toHaveBeenCalledWith('req-1', {
          bloodUnitId: 'bag',
          releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
          reason: 'Hémorragie massive',
          unitChoiceReason: 'Seule poche testée',
          patientInformation: 'NOT_POSSIBLE',
          physicianUnreachable: true,
          unreachableReason: 'Dr de garde appelé à 01 h 40, sans réponse',
        });
      });

      it('V381: the fallback is not offered when the hospital left it off, or the caller may not declare it', async () => {
        await openWithResult(['ROLE_LAB_TECHNICIAN']);
        for (const rule of [
          { fallbackEnabled: false, callerMayDeclare: false, countersignDeadlineHours: 24 },
          { fallbackEnabled: true, callerMayDeclare: false, countersignDeadlineHours: 24 },
        ]) {
          stubOptions({ incompatibleRule: rule });
          component.openIncompatibleRelease(withResult);
          // A stale tick from another form changes nothing: the server's rule decides what is asked.
          component.incompatibleUnreachable = true;
          fixture.detectChanges();
          expect(component.incompatibleFallbackOffered()).toBeFalse();
          expect(component.incompatibleDeclaring()).toBeFalse();
          expect(el('si-unreachable')).toBeNull();
          expect(el('si-fallback-off')).not.toBeNull();
          expect(document.getElementById('si-authoriser')).not.toBeNull();
        }
      });

      it('releasing with nothing filled in is refused on its fields', async () => {
        await openWithResult(['ROLE_LAB_TECHNICIAN']);
        stubOptions();
        el('incompatible-release')!.click();
        fixture.detectChanges();
        const marked = expectEmptySubmitRefusal(fixture, {
          button: '[data-testid="submit-incompatible-release"]',
        });
        expect(marked).toEqual([
          'si-unit',
          'si-choice',
          'si-authoriser',
          'si-informed',
          'si-reason',
        ]);
      });

      it('a refusal shows the server reason and keeps the form', async () => {
        await openWithResult(['ROLE_LAB_TECHNICIAN']);
        stubOptions();
        txSpy.emergencyRelease = jasmine
          .createSpy()
          .and.returnValue(throwError(() => ({ status: 400, error: { message: 'Groupe exclu' } })));
        component.openIncompatibleRelease(withResult);
        component.emergencyUnitId = 'bag';
        component.emergencyAuthoriserId = 'dir';
        component.emergencyReason = 'x';
        component.incompatibleChoiceReason = 'y';
        component.incompatiblePatientInformed = 'NOT_POSSIBLE';
        component.submitIncompatibleRelease();
        expect(toastSpy.error).toHaveBeenCalledWith('Groupe exclu');
        expect(component.showIncompatibleModal()).toBeTrue();
      });
    });

    it('the lab can record an incompatible result on a bag whose transfusion ended, on a closed request too', async () => {
      const closed = mockRequest({ id: 'req-1', status: 'COMPLETED', units: [] });
      const finished = {
        id: 'adm',
        requestId: 'req-1',
        bloodUnitId: 'bag',
        unitNumber: 'BAG-9',
        patientId: closed.patientId,
        patientName: closed.patientName,
        status: 'COMPLETED',
        startedAt: '2026-11-06T08:00:00',
        completedAt: '2026-11-06T10:00:00',
        volumeTransfusedMl: 300,
        administeredByName: 'Inf',
        verifiedByName: 'Inf 2',
        verificationMethod: null,
        stopReason: null,
        notes: null,
        reactions: [],
        crossmatch: null,
      } as TransfusionAdministrationResponse;
      await start(['ROLE_LAB_TECHNICIAN']);
      txSpy.getRequest.and.returnValue(of(closed));
      txSpy.listAdministrationsForPatient.and.returnValue(of([finished]));
      txSpy.recordCrossmatch = jasmine.createSpy().and.returnValue(of({} as CrossmatchResponse));
      component.openRequest(closed);
      fixture.detectChanges();
      el('late-incompatible-result')!.click();
      fixture.detectChanges();
      expect(el('late-result-hint')).not.toBeNull();
      component.crossmatchForm.incompatibilityReason = 'Anti-K';
      component.submitCrossmatch();
      expect(txSpy.recordCrossmatch).toHaveBeenCalledWith('req-1', {
        bloodUnitId: 'bag',
        compatible: false,
        method: undefined,
        incompatibilityReason: 'Anti-K',
      });
      expect(
        component.canRecordLateResult({
          ...finished,
          crossmatch: { compatible: false } as CrossmatchResponse,
        }),
      ).toBeFalse();
      expect(component.canRecordLateResult({ ...finished, status: 'IN_PROGRESS' })).toBeFalse();
    });

    it('the detail shows each release and the reminder to switch to type-specific blood', async () => {
      const released = mockRequest({
        id: 'req-1',
        urgency: 'EMERGENCY',
        status: 'ISSUED',
        emergencyReleases: [
          {
            id: 'r1',
            bloodUnitId: 'bag',
            unitNumber: 'BAG-1',
            releaseType: 'SEROLOGICALLY_INCOMPATIBLE',
            reason: 'Rien de compatible',
            authoriserName: 'Dr Sawadogo',
            issuedByName: 'Tech Ouedraogo',
            issuedAt: '2026-11-06T10:00:00',
            antibodyWarning: true,
            negativeUnobtainableStated: true,
            negativeUnitsShown: 'ONEG-1',
          },
          {
            id: 'r2',
            bloodUnitId: 'bag2',
            unitNumber: 'BAG-2',
            releaseType: 'UNCROSSMATCHED',
            reason: null,
            authoriserName: null,
            issuedByName: 'Tech Ouedraogo',
            issuedAt: '2026-11-06T09:00:00',
            antibodyWarning: false,
          },
        ],
      });
      await start(['ROLE_NURSE']);
      txSpy.getRequest.and.returnValue(of(released));
      component.openRequest(released);
      fixture.detectChanges();
      expect(el('type-specific-reminder')).not.toBeNull();
      expect(el('release-negative-statement')).not.toBeNull();
      const table = el('emergency-releases')!;
      expect(table.querySelectorAll('tbody tr').length).toBe(2);
      for (const text of [
        'BAG-1',
        'Rien de compatible',
        'Dr Sawadogo',
        'Tech Ouedraogo',
        'BAG-2',
      ]) {
        expect(table.textContent).toContain(text);
      }
    });

    it('is not offered to the bedside, or on a routine request', async () => {
      await open(['ROLE_NURSE']);
      expect(el('emergency-release')).toBeNull();
      expect(component.canReleaseInEmergency(mockRequest({ urgency: 'ROUTINE' }))).toBeFalse();
    });

    it('is offered for red cells only: O is no universal donor of plasma or platelets', async () => {
      await open(['ROLE_LAB_TECHNICIAN']);
      const on = (productType: TransfusionRequestResponse['productType']) =>
        component.canReleaseInEmergency(mockRequest({ urgency: 'EMERGENCY', productType }));
      expect(on('PACKED_RED_CELLS')).toBeTrue();
      expect(on('WHOLE_BLOOD')).toBeTrue();
      expect(on('FRESH_FROZEN_PLASMA')).toBeFalse();
      expect(on('PLATELETS')).toBeFalse();
      expect(on('CRYOPRECIPITATE')).toBeFalse();
    });
  });

  it('another request shows no cards of the one before, and a failed read leaves no actions', async () => {
    await start(['ROLE_NURSE']);
    const pending = new Subject<TransfusionRequestResponse>();
    txSpy.getRequest.and.returnValue(of(mockRequest({ id: 'req-2' })));
    txSpy.listAdministrationsForPatient.and.returnValue(new Subject());
    component.openRequest(mockRequest({ id: 'req-2' }));
    fixture.detectChanges();
    expect(el('suspected-reaction')).toBeNull();
    pending.complete();

    txSpy.getRequest.and.returnValue(of(issuedRequest));
    txSpy.listAdministrationsForPatient.and.returnValue(of([administration()]));
    component.openRequest(issuedRequest);
    fixture.detectChanges();
    expect(el('suspected-reaction')).toBeTruthy();
    txSpy.listAdministrationsForPatient.and.returnValue(throwError(() => new Error('down')));
    component.loadAdministrations();
    fixture.detectChanges();
    expect(el('suspected-reaction')).toBeNull();
  });

  it('a late refresh does not reopen a panel that was closed', async () => {
    await start(['ROLE_NURSE']);
    const late = new Subject<TransfusionRequestResponse>();
    txSpy.getRequest.and.returnValue(late);
    txSpy.stopAdministration.and.returnValue(of(administration({ status: 'STOPPED' })));
    component.confirmStop(administration(), 'Voie veineuse perdue');
    component.closeRequest();
    late.next(issuedRequest);
    expect(component.selectedRequest()).toBeNull();
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
