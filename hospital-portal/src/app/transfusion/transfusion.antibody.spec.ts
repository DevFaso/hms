import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { TransfusionComponent } from './transfusion';
import {
  AntibodyStatusResponse,
  CrossmatchResponse,
  PatientBloodGroupResponse,
  TransfusionRequestResponse,
  TransfusionService,
} from '../services/transfusion.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

/**
 * QA D3351 (r70), provisional rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN: the flag is shown wherever red cells
 * are asked for or released, a vital emergency is declared before it is sent, a compatible crossmatch carries
 * its attestations, and only the biologist is offered the antibody record.
 */
describe('TransfusionComponent: positive antibody screen (QA D3351)', () => {
  let fixture: ComponentFixture<TransfusionComponent>;
  let component: TransfusionComponent;
  let txSpy: jasmine.SpyObj<TransfusionService>;

  const flagged = (overrides: Partial<TransfusionRequestResponse> = {}) =>
    ({
      id: 'req-1',
      patientId: 'p1',
      patientName: 'Awa Traoré',
      productType: 'PACKED_RED_CELLS',
      unitsRequested: 1,
      indication: 'Anaemia',
      urgency: 'ROUTINE',
      status: 'REQUESTED',
      screenCurrent: true,
      units: [],
      crossmatches: [],
      alloImmunised: true,
      antibodyWorkupServes: false,
      ...overrides,
    }) as TransfusionRequestResponse;

  const status = (overrides: Partial<AntibodyStatusResponse> = {}): AntibodyStatusResponse => ({
    patientId: 'p1',
    alloImmunised: true,
    positiveScreenAt: '2026-11-20T09:00:00',
    identified: false,
    antibodySpecificities: null,
    workups: [],
    ...overrides,
  });

  async function setUp(roles: string[]): Promise<void> {
    txSpy = jasmine.createSpyObj('TransfusionService', [
      'listRequests',
      'getRequest',
      'createRequest',
      'listUnits',
      'listAssignableUnits',
      'getCurrentBloodGroup',
      'recordCrossmatch',
      'listAdministrationsForPatient',
      'listVerifierCandidates',
      'getAntibodyStatus',
      'recordAntibodyWorkup',
      'emergencyReleaseOptions',
    ]);
    txSpy.listAdministrationsForPatient.and.returnValue(of([]));
    txSpy.listVerifierCandidates.and.returnValue(of([]));
    txSpy.listRequests.and.returnValue(of([flagged()]));
    txSpy.listUnits.and.returnValue(of([]));
    txSpy.listAssignableUnits.and.returnValue(of([]));
    txSpy.getAntibodyStatus.and.returnValue(of(status()));
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
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(TransfusionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const open = (request: TransfusionRequestResponse) => {
    component.selectedRequest.set(request);
    fixture.detectChanges();
  };

  it('shows the permanent flag on the request, and what red cells still wait for', async () => {
    await setUp(['ROLE_DOCTOR']);
    open(flagged());
    expect(el().querySelector('[data-testid="allo-immunised"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="antibody-workup-missing"]')).not.toBeNull();
    // A prescriber is not offered the antibody record.
    expect(component.canRecordAntibody).toBeFalse();
    expect(el().querySelector('[data-testid="antibody-record"]')).toBeNull();

    open(flagged({ alloImmunised: false }));
    expect(el().querySelector('[data-testid="allo-immunised"]')).toBeNull();
  });

  it('a red-cell emergency for an allo-immunised patient is declared before it is sent', async () => {
    await setUp(['ROLE_DOCTOR']);
    txSpy.createRequest.and.returnValue(of(flagged({ urgency: 'EMERGENCY' })));
    component.patientGroup.set({ alloImmunised: true } as PatientBloodGroupResponse);
    component.requestForm.patientId = 'p1';
    component.requestForm.indication = 'Postpartum haemorrhage';

    // Routine and urgent requests declare nothing: they wait for the compatible unit.
    expect(component.needsVitalUrgency).toBeFalse();
    component.requestForm.urgency = 'URGENT';
    expect(component.needsVitalUrgency).toBeFalse();
    component.requestForm.urgency = 'EMERGENCY';
    component.requestForm.productType = 'PLATELETS';
    expect(component.needsVitalUrgency).toBeFalse();
    component.requestForm.productType = 'PACKED_RED_CELLS';
    expect(component.needsVitalUrgency).toBeTrue();

    expect(component.vitalUrgencyDeclared).toBeFalse();
    component.submitRequest();
    expect(txSpy.createRequest).not.toHaveBeenCalled();

    component.requestForm.vitalUrgencyLevel = 'UVI';
    component.requestForm.vitalUrgencyReason = ' Haemorrhagic shock ';
    expect(component.vitalUrgencyDeclared).toBeFalse();
    component.requestForm.preTransfusionSampleDrawn = true;
    expect(component.vitalUrgencyDeclared).toBeTrue();
    component.submitRequest();
    expect(txSpy.createRequest).toHaveBeenCalledWith(
      jasmine.objectContaining({
        urgency: 'EMERGENCY',
        vitalUrgencyLevel: 'UVI',
        vitalUrgencyReason: 'Haemorrhagic shock',
        preTransfusionSampleDrawn: true,
      }),
    );
  });

  it('a patient with no antibody is asked nothing more, and nothing more is sent', async () => {
    await setUp(['ROLE_DOCTOR']);
    txSpy.createRequest.and.returnValue(of(flagged({ alloImmunised: false })));
    component.patientGroup.set({ alloImmunised: false } as PatientBloodGroupResponse);
    component.requestForm.patientId = 'p1';
    component.requestForm.indication = 'Haemorrhage';
    component.requestForm.urgency = 'EMERGENCY';
    expect(component.needsVitalUrgency).toBeFalse();
    component.submitRequest();
    const sent = txSpy.createRequest.calls.mostRecent().args[0];
    expect(sent.vitalUrgencyLevel).toBeUndefined();
    expect(sent.preTransfusionSampleDrawn).toBeUndefined();
  });

  it('a compatible crossmatch for an allo-immunised patient carries the sample and the three attestations', async () => {
    await setUp(['ROLE_LAB_TECHNICIAN']);
    txSpy.recordCrossmatch.and.returnValue(of({} as CrossmatchResponse));
    txSpy.getRequest.and.returnValue(of(flagged()));
    open(flagged({ antibodyWorkupServes: true }));
    component.crossmatchForm.bloodUnitId = 'u1';
    component.crossmatchForm.compatible = true;
    expect(component.crossmatchNeedsCompatibilisation).toBeTrue();
    expect(component.crossmatchCompatibilised).toBeFalse();
    component.submitCrossmatch();
    expect(txSpy.recordCrossmatch).not.toHaveBeenCalled();

    component.crossmatchForm.sampleCollectedAt = '2026-11-22T08:00';
    component.crossmatchForm.antiglobulinMethod = true;
    component.crossmatchForm.antigenNegative = true;
    expect(component.crossmatchCompatibilised).toBeFalse();
    component.crossmatchForm.rhKellMatched = true;
    component.submitCrossmatch();
    expect(txSpy.recordCrossmatch).toHaveBeenCalledWith(
      'req-1',
      jasmine.objectContaining({
        compatible: true,
        sampleCollectedAt: '2026-11-22T08:00',
        antiglobulinMethod: true,
        antigenNegative: true,
        rhKellMatched: true,
      }),
    );

    // An incompatible result is never held by the attestations.
    component.crossmatchForm.compatible = false;
    expect(component.crossmatchNeedsCompatibilisation).toBeFalse();
    expect(component.crossmatchCompatibilised).toBeTrue();
  });

  it('only the biologist is offered the antibody record, and a referral decision names this request', async () => {
    await setUp(['ROLE_LAB_DIRECTOR']);
    txSpy.recordAntibodyWorkup.and.returnValue(of(status({ identified: true })));
    txSpy.getRequest.and.returnValue(of(flagged({ antibodyWorkupServes: true })));
    open(flagged());
    expect(component.canRecordAntibody).toBeTrue();
    const record = el().querySelector<HTMLButtonElement>('[data-testid="antibody-record"]')!;
    expect(record).not.toBeNull();
    record.click();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="antibody-modal"]')).not.toBeNull();

    // Identified: the specificity is required.
    expect(component.antibodyFormComplete).toBeFalse();
    component.submitAntibodyWorkup();
    expect(txSpy.recordAntibodyWorkup).not.toHaveBeenCalled();
    component.antibodyForm.antibodySpecificities = ' anti-K ';
    component.submitAntibodyWorkup();
    expect(txSpy.recordAntibodyWorkup).toHaveBeenCalledWith(
      jasmine.objectContaining({
        patientId: 'p1',
        status: 'IDENTIFIED',
        antibodySpecificities: 'anti-K',
        transfusionRequestId: undefined,
      }),
    );

    // Referred: the centre, the reason, and this very request.
    component.openAntibodyWorkup();
    component.antibodyForm.status = 'REFERRED';
    component.antibodyForm.referralCentre = 'CNTS Ouagadougou';
    expect(component.antibodyFormComplete).toBeFalse();
    component.antibodyForm.decisionReason = 'Active bleeding';
    component.submitAntibodyWorkup();
    expect(txSpy.recordAntibodyWorkup.calls.mostRecent().args[0]).toEqual(
      jasmine.objectContaining({
        status: 'REFERRED',
        referralCentre: 'CNTS Ouagadougou',
        decisionReason: 'Active bleeding',
        transfusionRequestId: 'req-1',
      }),
    );
  });

  it('an empty antibody record is refused in the dialog and names its field', async () => {
    await setUp(['ROLE_LAB_DIRECTOR']);
    open(flagged());
    component.openAntibodyWorkup();
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="submit-antibody-workup"]',
    });
    expect(marked).toEqual(['ab-specificities']);
    expect(txSpy.recordAntibodyWorkup).not.toHaveBeenCalled();
  });

  it('a laboratory technician and a manager are not offered the antibody record', async () => {
    await setUp(['ROLE_LAB_TECHNICIAN', 'ROLE_LAB_MANAGER', 'ROLE_LAB_SCIENTIST']);
    open(flagged());
    expect(component.canRecordAntibody).toBeFalse();
    expect(el().querySelector('[data-testid="antibody-record"]')).toBeNull();
  });
});
