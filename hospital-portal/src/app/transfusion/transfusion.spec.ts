import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { TransfusionComponent } from './transfusion';
import {
  BloodUnitResponse,
  CrossmatchResponse,
  PatientBloodGroupResponse,
  TransfusionReactionResponse,
  TransfusionRequestResponse,
  TransfusionService,
} from '../services/transfusion.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';

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
    ]);
    txSpy.listRequests.and.returnValue(of([mockRequest()]));
    txSpy.listUnits.and.returnValue(of([mockUnit()]));
    txSpy.listAssignableUnits.and.returnValue(of([mockUnit()]));

    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const roleCtx = {
      hasAnyActiveRole: () => true,
      isSuperAdmin: () => false,
      activeHospitalId: 'h1',
    } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [TransfusionComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
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
    component.hangForm.verifiedByStaffId = '   ';
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

  it('does not record a reaction with no signs described', () => {
    component.openReaction('adm-1');
    component.reactionForm.onsetAt = '2026-08-25T10:00';
    component.reactionForm.signsSymptoms = '';
    component.submitReaction();
    expect(txSpy.recordReaction).not.toHaveBeenCalled();
  });

  it('cancelling a request needs a reason from the operator', () => {
    spyOn(window, 'prompt').and.returnValue('  ');
    component.cancelRequest(mockRequest());
    expect(txSpy.cancelRequest).not.toHaveBeenCalled();
  });

  it('cancels with the supplied reason', () => {
    spyOn(window, 'prompt').and.returnValue('Patient stabilised');
    txSpy.cancelRequest.and.returnValue(of(mockRequest({ status: 'CANCELLED' })));
    component.cancelRequest(mockRequest());
    expect(txSpy.cancelRequest).toHaveBeenCalledWith('req-1', 'Patient stabilised');
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
    ]);
    txSpy.listRequests.and.returnValue(of([mockRequest()]));
    txSpy.listUnits.and.returnValue(of([]));
    txSpy.listAssignableUnits.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [TransfusionComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: TransfusionService, useValue: txSpy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        {
          provide: RoleContextService,
          useValue: {
            hasAnyActiveRole: () => true,
            isSuperAdmin: () => false,
            activeHospitalId: 'h1',
          } as unknown as RoleContextService,
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
