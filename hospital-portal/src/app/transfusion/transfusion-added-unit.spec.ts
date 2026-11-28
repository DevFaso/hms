import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { TransfusionComponent } from './transfusion';
import {
  BloodUnitResponse,
  EmergencyReleaseOptions,
  TransfusionRequestResponse,
  TransfusionService,
} from '../services/transfusion.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';

/**
 * QA D3906: a bag beyond the units requested is refused by the server on the field `additionalUnit`. The page
 * asks « Ajouter une unité ? » with the server's reason and, on the step, sends the same issue again with it.
 * It never turns the refusal into a dead end, and never sends the step unasked.
 */
function request(overrides: Partial<TransfusionRequestResponse> = {}): TransfusionRequestResponse {
  return {
    id: 'req-1',
    patientId: 'p1',
    patientName: 'Fatimata FTest',
    productType: 'PACKED_RED_CELLS',
    unitsRequested: 1,
    indication: 'Hémorragie',
    urgency: 'EMERGENCY',
    status: 'ISSUED',
    screenCurrent: true,
    units: [],
    crossmatches: [],
    ...overrides,
  } as TransfusionRequestResponse;
}

function unit(overrides: Partial<BloodUnitResponse> = {}): BloodUnitResponse {
  return {
    id: 'u2',
    unitNumber: 'UNIT-ON2',
    productType: 'PACKED_RED_CELLS',
    aboGroup: 'O',
    rhFactor: 'NEGATIVE',
    expiresOn: '2026-12-20',
    expired: false,
    status: 'AVAILABLE',
    ...overrides,
  } as BloodUnitResponse;
}

const BEYOND = {
  status: 400,
  error: {
    status: 400,
    message: 'La poche UNIT-ON2 dépasserait la demande : 1 unité demandée, 1 déjà sortie.',
    field: 'additionalUnit',
  },
};

describe('TransfusionComponent — D3906 « ajouter une unité »', () => {
  let fixture: ComponentFixture<TransfusionComponent>;
  let component: TransfusionComponent;
  let tx: jasmine.SpyObj<TransfusionService>;
  let toast: jasmine.SpyObj<ToastService>;

  beforeEach(() => {
    tx = jasmine.createSpyObj('TransfusionService', [
      'listRequests',
      'getRequest',
      'listUnits',
      'listAssignableUnits',
      'listAdministrationsForPatient',
      'listVerifierCandidates',
      'getAntibodyStatus',
      'getCurrentBloodGroup',
      'issueUnit',
      'emergencyRelease',
      'emergencyReleaseOptions',
    ]);
    tx.listRequests.and.returnValue(of([request()]));
    tx.getRequest.and.returnValue(of(request()));
    tx.listUnits.and.returnValue(of([unit()]));
    tx.listAssignableUnits.and.returnValue(of([]));
    tx.listAdministrationsForPatient.and.returnValue(of([]));
    tx.listVerifierCandidates.and.returnValue(of([]));
    tx.emergencyReleaseOptions.and.returnValue(
      of({
        units: [],
        incompatibleUnits: [],
        negativeStock: [],
      } as unknown as EmergencyReleaseOptions),
    );
    toast = jasmine.createSpyObj('ToastService', ['success', 'error', 'warning', 'info']);

    TestBed.configureTestingModule({
      imports: [TransfusionComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: TransfusionService, useValue: tx },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_LAB_SCIENTIST'],
          }),
        },
      ],
    });
    fixture = TestBed.createComponent(TransfusionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.selectedRequest.set(request());
  });

  const q = (testid: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testid}"]`);

  it('asks before a bag beyond the request, then sends the issue again with the step', () => {
    tx.issueUnit.and.returnValues(
      throwError(() => BEYOND),
      of(unit({ status: 'ISSUED' })),
    );

    component.issueUnit(unit());
    fixture.detectChanges();

    expect(tx.issueUnit).toHaveBeenCalledOnceWith('req-1', 'u2', false);
    expect(toast.error).not.toHaveBeenCalled();
    const prompt = q('confirm-dialog');
    expect(prompt).not.toBeNull();
    expect(prompt?.textContent).toContain('1 unité demandée, 1 déjà sortie');

    (q('confirm-dialog-confirm') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(tx.issueUnit).toHaveBeenCalledTimes(2);
    expect(tx.issueUnit.calls.mostRecent().args).toEqual(['req-1', 'u2', true]);
    expect(toast.success).toHaveBeenCalled();
    expect(q('confirm-dialog')).toBeNull();
  });

  it('cancelling the prompt sends nothing more', () => {
    tx.issueUnit.and.returnValue(throwError(() => BEYOND));

    component.issueUnit(unit());
    fixture.detectChanges();
    (q('confirm-dialog-cancel') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(tx.issueUnit).toHaveBeenCalledTimes(1);
    expect(q('confirm-dialog')).toBeNull();
  });

  it('any other refusal is a refusal, not a prompt', () => {
    tx.issueUnit.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'Poche expirée', field: 'bloodUnitId' },
      })),
    );

    component.issueUnit(unit());
    fixture.detectChanges();

    expect(q('confirm-dialog')).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Poche expirée');
  });

  it('the emergency release form asks the same way on its plain O negative issue', () => {
    tx.issueUnit.and.returnValues(
      throwError(() => BEYOND),
      of(unit({ status: 'ISSUED' })),
    );
    component.emergencyUnitId = 'u2';

    component.submitEmergencyRelease();
    fixture.detectChanges();
    expect(q('confirm-dialog')).not.toBeNull();
    component.confirmAddUnit();

    expect(tx.issueUnit.calls.mostRecent().args).toEqual(['req-1', 'u2', true]);
  });

  it('the incompatible release sends the step in its body when asked again', () => {
    tx.emergencyRelease.and.returnValues(
      throwError(() => BEYOND),
      of(unit({ status: 'ISSUED' })),
    );
    component.emergencyUnitId = 'u2';
    component.emergencyReason = 'Hémorragie massive';
    component.incompatibleChoiceReason = 'Seule poche';
    component.incompatiblePatientInformed = 'NOT_POSSIBLE';
    component.emergencyAuthoriserId = 'doc-1';

    component.submitIncompatibleRelease();
    fixture.detectChanges();
    expect(tx.emergencyRelease.calls.mostRecent().args[1].additionalUnit).toBeUndefined();
    expect(q('confirm-dialog')).not.toBeNull();
    component.confirmAddUnit();

    expect(tx.emergencyRelease).toHaveBeenCalledTimes(2);
    expect(tx.emergencyRelease.calls.mostRecent().args[1].additionalUnit).toBeTrue();
  });
});
