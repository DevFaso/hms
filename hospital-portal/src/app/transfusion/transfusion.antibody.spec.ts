import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

import { TransfusionComponent } from './transfusion';
import {
  AntibodyStatusResponse,
  CrossmatchResponse,
  PatientBloodGroupResponse,
  TransfusionRequestResponse,
  TransfusionService,
} from '../services/transfusion.service';
import { PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { toServerDateTime } from '../shared/date-utils';
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
        // Sent as the server reads times, not as the browser's wall clock.
        sampleCollectedAt: toServerDateTime('2026-11-22T08:00'),
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

  it('a flag held at another hospital shows on the request form with no blood group here, and the emergency is declared', async () => {
    await setUp(['ROLE_DOCTOR']);
    // Never typed at this hospital (QA D3352: 204, read as null); a positive screen and an identified antibody
    // are on record at another one.
    txSpy.getCurrentBloodGroup.and.returnValue(of(null));
    txSpy.getAntibodyStatus.and.returnValue(
      of(
        status({
          identified: true,
          antibodySpecificities: 'anti-K',
          identifiedHospitalName: 'CHU Yalgado',
          identifiedAt: '2026-11-02T10:00:00',
          positiveScreenHospitalName: 'CHU Yalgado',
          otherHospitalNames: ['CHU Yalgado'],
        }),
      ),
    );
    component.openNewRequest();
    component.onPatientPicked({
      id: 'p1',
      firstName: 'Awa',
      lastName: 'Traoré',
    } as PatientResponse);
    fixture.detectChanges();

    expect(txSpy.getAntibodyStatus).toHaveBeenCalledWith('p1');
    expect(component.patientGroup()).toBeNull();
    expect(component.requestPatientAlloImmunised).toBeTrue();
    const known = el().querySelector('[data-testid="request-antibody-known"]');
    expect(known).not.toBeNull();
    // The fact line and, with no group line to carry it, the requirement.
    expect(known!.textContent).toContain('TRANSFUSION.ANTIBODY_KNOWN_IDENTIFIED');
    expect(known!.textContent).toContain('TRANSFUSION.ALLO_IMMUNISED_REQUEST');

    component.requestForm.urgency = 'EMERGENCY';
    component.requestForm.productType = 'PACKED_RED_CELLS';
    expect(component.needsVitalUrgency).toBeTrue();
    expect(component.vitalUrgencyDeclared).toBeFalse();

    // Another patient is picked: what was known of the first one is not carried over.
    txSpy.getAntibodyStatus.and.returnValue(of(status({ patientId: 'p2', alloImmunised: false })));
    component.onPatientPicked({ id: 'p2', firstName: 'Fatou', lastName: 'Sow' } as PatientResponse);
    fixture.detectChanges();
    expect(component.requestPatientAlloImmunised).toBeFalse();
    expect(el().querySelector('[data-testid="request-antibody-known"]')).toBeNull();
    expect(component.needsVitalUrgency).toBeFalse();
  });

  it('V380: an RhD positive exposure recorded at another hospital shows on the request form with no blood group here', async () => {
    await setUp(['ROLE_DOCTOR']);
    txSpy.getCurrentBloodGroup.and.returnValue(of(null));
    txSpy.getAntibodyStatus.and.returnValue(
      of(
        status({
          alloImmunised: false,
          positiveScreenAt: null,
          rhdPositiveExposed: true,
          rhdPositiveExposureConfirmed: true,
          rhdPositiveExposedAt: '2026-11-25T22:00:00',
          rhdPositiveExposedHospitalName: 'CHU Yalgado',
        }),
      ),
    );
    component.openNewRequest();
    component.onPatientPicked({
      id: 'p1',
      firstName: 'Awa',
      lastName: 'Traoré',
    } as PatientResponse);
    fixture.detectChanges();

    expect(component.patientGroup()).toBeNull();
    const flag = el().querySelector('[data-testid="request-rhd-exposed"]');
    expect(flag).not.toBeNull();
    expect(flag!.textContent).toContain('TRANSFUSION.OPOS.EXPOSED_BANNER');
    expect(el().querySelector('[data-testid="request-antibody-known"]')).toBeNull();

    // Issued elsewhere and never recorded as given: shown, and said to be unconfirmed.
    txSpy.getAntibodyStatus.and.returnValue(
      of(
        status({
          patientId: 'p2',
          alloImmunised: false,
          rhdPositiveExposed: true,
          rhdPositiveExposureConfirmed: false,
        }),
      ),
    );
    component.onPatientPicked({ id: 'p2', firstName: 'Fatou', lastName: 'Sow' } as PatientResponse);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="request-rhd-exposed"]')!.textContent).toContain(
      'TRANSFUSION.OPOS.EXPOSED_BANNER_UNCONFIRMED',
    );
  });

  it('a positive screen not identified says so, and an answer for a patient no longer picked is dropped', async () => {
    await setUp(['ROLE_DOCTOR']);
    txSpy.getCurrentBloodGroup.and.returnValue(of(null));
    const late = new Subject<AntibodyStatusResponse>();
    txSpy.getAntibodyStatus.and.returnValue(late.asObservable());
    component.openNewRequest();
    component.onPatientPicked({
      id: 'p1',
      firstName: 'Awa',
      lastName: 'Traoré',
    } as PatientResponse);
    component.onPatientPicked(null);
    late.next(
      status({ positiveScreenHospitalName: 'CMA Pissy', otherHospitalNames: ['CMA Pissy'] }),
    );
    fixture.detectChanges();
    expect(component.requestAntibody()).toBeNull();

    txSpy.getAntibodyStatus.and.returnValue(
      of(status({ positiveScreenHospitalName: 'CMA Pissy', otherHospitalNames: ['CMA Pissy'] })),
    );
    component.onPatientPicked({
      id: 'p1',
      firstName: 'Awa',
      lastName: 'Traoré',
    } as PatientResponse);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="request-antibody-known"]')!.textContent).toContain(
      'TRANSFUSION.ANTIBODY_KNOWN_NOT_IDENTIFIED',
    );

    // The read failing leaves the form usable, with nothing claimed.
    txSpy.getAntibodyStatus.and.returnValue(throwError(() => new Error('500')));
    component.onPatientPicked({
      id: 'p1',
      firstName: 'Awa',
      lastName: 'Traoré',
    } as PatientResponse);
    fixture.detectChanges();
    expect(component.requestAntibody()).toBeNull();
    expect(el().querySelector('[data-testid="request-antibody-known"]')).toBeNull();
  });

  it('the request says which other hospital the safety information comes from', async () => {
    await setUp(['ROLE_LAB_SCIENTIST']);
    txSpy.getRequest.and.returnValue(of(flagged()));
    txSpy.getAntibodyStatus.and.returnValue(
      of(
        status({
          identified: true,
          antibodySpecificities: 'anti-K',
          otherHospitalNames: ['CHU Yalgado'],
        }),
      ),
    );
    component.openRequest(flagged());
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="antibody-other-hospital"]')).not.toBeNull();

    txSpy.getAntibodyStatus.and.returnValue(of(status({ otherHospitalNames: [] })));
    component.openRequest(flagged());
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="antibody-other-hospital"]')).toBeNull();
  });

  it('review H1: the nurse who gives the unit opens a flagged request, and reads the antibody and where it comes from', async () => {
    // The server refused her this read for one build (403): the portal asks for it whenever it opens a flagged
    // request, and the refusal took the request off her screen, for the allo-immunised patients only.
    await setUp(['ROLE_NURSE']);
    txSpy.getRequest.and.returnValue(of(flagged({ status: 'ISSUED' })));
    txSpy.getAntibodyStatus.and.returnValue(
      of(
        status({
          identified: true,
          antibodySpecificities: 'anti-K',
          identifiedHospitalName: 'CHU Yalgado',
          otherHospitalNames: ['CHU Yalgado'],
        }),
      ),
    );

    component.openRequest(flagged({ status: 'ISSUED' }));
    fixture.detectChanges();

    expect(txSpy.getAntibodyStatus).toHaveBeenCalledWith('p1');
    expect(component.selectedRequest()?.id).toBe('req-1');
    expect(el().querySelector('[data-testid="allo-immunised"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="antibody-identified"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="antibody-other-hospital"]')).not.toBeNull();
    // She reads; she does not record the antibody.
    expect(component.canRecordAntibody).toBeFalse();
    expect(el().querySelector('[data-testid="antibody-record"]')).toBeNull();
  });

  it('a status that cannot be read leaves the request on the screen, with its flag', async () => {
    await setUp(['ROLE_NURSE']);
    txSpy.getRequest.and.returnValue(of(flagged()));
    txSpy.getAntibodyStatus.and.returnValue(throwError(() => new Error('503')));

    component.openRequest(flagged());
    fixture.detectChanges();

    expect(component.selectedRequest()?.id).toBe('req-1');
    expect(component.antibodyStatus()).toBeNull();
    expect(el().querySelector('[data-testid="allo-immunised"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="antibody-identified"]')).toBeNull();
  });
});
