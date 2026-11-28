import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { TransfusionComponent } from './transfusion';
import {
  BloodUnitResponse,
  TransfusionAdministrationResponse,
  TransfusionRequestResponse,
  TransfusionService,
  VerifierCandidate,
} from '../services/transfusion.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { roleContextStub } from '../testing/role-context.stub';
import { answerReasonDialog } from '../shared/reason-dialog/reason-dialog.testing';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

/**
 * QA D4141 and D4178 (r81).
 *
 * D4141: the second bedside check is confirmed by the second checker, with their own factor: the
 * dialog asks for it, sends it once and never keeps it.
 *
 * D4178: an issued bag is out of the laboratory. It is neither written off from the stock list nor
 * brought back by a cancelled request: the laboratory records its return, and what it found.
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
    id: 'u1',
    unitNumber: 'UNIT-OP4',
    productType: 'PACKED_RED_CELLS',
    aboGroup: 'O',
    rhFactor: 'POSITIVE',
    expiresOn: '2026-12-20',
    expired: false,
    status: 'ISSUED',
    ...overrides,
  } as BloodUnitResponse;
}

describe('TransfusionComponent — r81 (D4141 second check, D4178 return of an issued bag)', () => {
  let fixture: ComponentFixture<TransfusionComponent>;
  let component: TransfusionComponent;
  let tx: jasmine.SpyObj<TransfusionService>;
  let toast: jasmine.SpyObj<ToastService>;

  function setUp(roles: string[], units: BloodUnitResponse[] = [unit()]): void {
    tx = jasmine.createSpyObj('TransfusionService', [
      'listRequests',
      'getRequest',
      'cancelRequest',
      'listUnits',
      'listAssignableUnits',
      'discardUnit',
      'recordUnitReturn',
      'startAdministration',
      'listAdministrationsForPatient',
      'listVerifierCandidates',
      'getAntibodyStatus',
      'getCurrentBloodGroup',
    ]);
    tx.listRequests.and.returnValue(of([request()]));
    tx.getRequest.and.returnValue(of(request()));
    tx.listUnits.and.returnValue(of(units));
    tx.listAssignableUnits.and.returnValue(of([]));
    tx.listAdministrationsForPatient.and.returnValue(of([]));
    tx.listVerifierCandidates.and.returnValue(of([]));
    tx.startAdministration.and.returnValue(
      of({ id: 'adm-1' } as TransfusionAdministrationResponse),
    );
    tx.recordUnitReturn.and.returnValue(of(unit({ status: 'RETURNED' })));
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
          useValue: roleContextStub({ superAdmin: false, hospitalId: 'h1', roles }),
        },
      ],
    });
    fixture = TestBed.createComponent(TransfusionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const q = (testid: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testid}"]`);

  function openHangWith(candidates: VerifierCandidate[]): void {
    tx.listVerifierCandidates.and.returnValue(of(candidates));
    component.selectedRequest.set(request());
    component.openHang(unit());
    fixture.detectChanges();
  }

  // ── D4141 ──────────────────────────────────────────────────────────────

  it('naming the second checker is not enough: nothing is sent without their confirmation', () => {
    setUp(['ROLE_NURSE']);
    openHangWith([
      { userId: 'user-2', displayName: 'Aminata Sawadogo', confirmationMethod: 'PASSWORD' },
    ]);
    component.hangForm.verifiedByUserId = 'user-2';
    fixture.detectChanges();

    expect(q('hang-verifier-secret')).not.toBeNull();
    expect(component.hangIncomplete()).toBeTrue();
    component.submitHang();

    expect(tx.startAdministration).not.toHaveBeenCalled();
  });

  it('asks a password of a colleague without an authenticator, hidden as it is typed', () => {
    setUp(['ROLE_NURSE']);
    openHangWith([
      { userId: 'user-2', displayName: 'Aminata Sawadogo', confirmationMethod: 'PASSWORD' },
    ]);
    component.hangForm.verifiedByUserId = 'user-2';
    fixture.detectChanges();

    const field = q('hang-verifier-secret') as HTMLInputElement;
    expect(field.type).toBe('password');
    expect(field.getAttribute('autocomplete')).toBe('off');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'TRANSFUSION.VERIFIER_CONFIRM.PASSWORD_LABEL',
    );
  });

  it('asks the authenticator code of a colleague who has one', () => {
    setUp(['ROLE_NURSE']);
    openHangWith([{ userId: 'user-3', displayName: 'Issa Barry', confirmationMethod: 'TOTP' }]);
    component.hangForm.verifiedByUserId = 'user-3';
    fixture.detectChanges();

    const field = q('hang-verifier-secret') as HTMLInputElement;
    expect(field.type).toBe('text');
    expect(field.getAttribute('autocomplete')).toBe('one-time-code');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'TRANSFUSION.VERIFIER_CONFIRM.CODE_LABEL',
    );
  });

  it('a colleague who cannot confirm cannot be the second checker', () => {
    setUp(['ROLE_NURSE']);
    openHangWith([{ userId: 'user-4', displayName: 'Ali Sow', confirmationMethod: 'UNAVAILABLE' }]);
    component.hangForm.verifiedByUserId = 'user-4';
    component.hangForm.verifierSecret = 'anything';
    fixture.detectChanges();

    expect(q('hang-verifier-unavailable')).not.toBeNull();
    expect(q('hang-verifier-secret')).toBeNull();
    component.submitHang();
    expect(tx.startAdministration).not.toHaveBeenCalled();
  });

  it('sends the checker’s factor once with the hang, and keeps none of it', () => {
    setUp(['ROLE_NURSE']);
    openHangWith([
      { userId: 'user-2', displayName: 'Aminata Sawadogo', confirmationMethod: 'PASSWORD' },
    ]);
    component.hangForm.verifiedByUserId = 'user-2';
    component.hangForm.verifierSecret = 'Her-Own-Password-1';

    component.submitHang();

    expect(tx.startAdministration).toHaveBeenCalledWith(
      jasmine.objectContaining({
        requestId: 'req-1',
        bloodUnitId: 'u1',
        verifiedByUserId: 'user-2',
        verifierSecret: 'Her-Own-Password-1',
      }),
    );
    expect(component.hangForm.verifierSecret).toBe('');
  });

  it('a refused confirmation shows the server’s reason, keeps the dialog and empties the field', () => {
    setUp(['ROLE_NURSE']);
    openHangWith([
      { userId: 'user-2', displayName: 'Aminata Sawadogo', confirmationMethod: 'PASSWORD' },
    ]);
    tx.startAdministration.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'La confirmation du second contrôleur n’a pas été acceptée.' },
      })),
    );
    component.hangForm.verifiedByUserId = 'user-2';
    component.hangForm.verifierSecret = 'a guess';

    component.submitHang();

    expect(toast.error).toHaveBeenCalled();
    expect(component.showHangModal()).toBeTrue();
    expect(component.hangForm.verifierSecret).toBe('');
  });

  it('what one colleague typed is not kept for another, nor after the dialog closes', () => {
    setUp(['ROLE_NURSE']);
    openHangWith([
      { userId: 'user-2', displayName: 'Aminata Sawadogo', confirmationMethod: 'PASSWORD' },
      { userId: 'user-3', displayName: 'Issa Barry', confirmationMethod: 'TOTP' },
    ]);
    component.hangForm.verifiedByUserId = 'user-2';
    component.hangForm.verifierSecret = 'Her-Own-Password-1';
    component.hangForm.verifiedByUserId = 'user-3';
    component.onHangVerifierChange();
    expect(component.hangForm.verifierSecret).toBe('');

    component.hangForm.verifierSecret = '123456';
    component.closeHang();
    expect(component.hangForm.verifierSecret).toBe('');
  });

  // ── D4178 ──────────────────────────────────────────────────────────────

  it('an issued bag offers « record the return », never the write-off', () => {
    setUp(['ROLE_LAB_SCIENTIST'], [unit({ id: 'out', status: 'ISSUED', awaitingReturn: true })]);
    component.setTab('units');
    fixture.detectChanges();

    expect(q('unit-return-out')).not.toBeNull();
    expect(q('unit-awaiting-return-out')).not.toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('.action-link.delete')).toBeNull();
  });

  it('a bag in stock keeps the write-off and offers no return', () => {
    setUp(['ROLE_LAB_SCIENTIST'], [unit({ id: 'in', status: 'AVAILABLE' })]);
    component.setTab('units');
    fixture.detectChanges();

    expect(q('unit-return-in')).toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.action-link.delete'),
    ).not.toBeNull();
  });

  it('a nurse or a doctor is not offered the return: it is the laboratory’s record', () => {
    setUp(
      ['ROLE_NURSE', 'ROLE_DOCTOR'],
      [unit({ id: 'out', status: 'ISSUED', awaitingReturn: true })],
    );
    component.setTab('units');
    fixture.detectChanges();

    expect(q('unit-return-out')).toBeNull();
    expect(q('unit-awaiting-return-out')).not.toBeNull();
  });

  it('recording a return with nothing chosen is refused on the outcome', () => {
    setUp(['ROLE_LAB_SCIENTIST']);
    document.body.appendChild(fixture.nativeElement);
    try {
      component.openReturn(unit());
      fixture.detectChanges();
      const marked = expectEmptySubmitRefusal(fixture, {
        button: '[data-testid="return-submit"]',
        alsoMarked: ['return-outcome-FIT_FOR_STOCK'],
        sent: tx.recordUnitReturn,
      });
      expect(marked).toEqual(['return-outcome-FIT_FOR_STOCK']);
    } finally {
      (fixture.nativeElement as HTMLElement).remove();
    }
  });

  it('a destroyed bag recorded without what was found is refused on the note', () => {
    setUp(['ROLE_LAB_SCIENTIST']);
    document.body.appendChild(fixture.nativeElement);
    try {
      component.openReturn(unit());
      component.returnForm.outcome = 'UNFIT_DESTROYED';
      fixture.detectChanges();
      const marked = expectEmptySubmitRefusal(fixture, {
        button: '[data-testid="return-submit"]',
        sent: tx.recordUnitReturn,
      });
      expect(marked).toEqual(['return-note']);
    } finally {
      (fixture.nativeElement as HTMLElement).remove();
    }
  });

  it('a bag goes back into stock only on the laboratory’s two statements', () => {
    setUp(['ROLE_LAB_SCIENTIST']);
    component.openReturn(unit());
    component.returnForm.outcome = 'FIT_FOR_STOCK';
    component.returnForm.coldChainKept = true;
    expect(component.returnIncomplete()).toBeTrue();
    expect(component.returnMissingFields()).toEqual(['return-intact']);
    component.submitReturn();
    expect(tx.recordUnitReturn).not.toHaveBeenCalled();

    component.returnForm.bagIntact = true;
    component.returnForm.note = ' 20 min hors du laboratoire ';
    component.submitReturn();

    expect(tx.recordUnitReturn).toHaveBeenCalledWith('u1', {
      outcome: 'FIT_FOR_STOCK',
      coldChainKept: true,
      bagIntact: true,
      note: '20 min hors du laboratoire',
    });
    expect(toast.success).toHaveBeenCalledWith('TRANSFUSION.RETURN.DONE_RESTOCKED');
    expect(component.returningUnit()).toBeNull();
  });

  it('a bag destroyed or not recovered needs what was found, and sends no statement', () => {
    setUp(['ROLE_LAB_SCIENTIST']);
    component.openReturn(unit());
    component.returnForm.outcome = 'NOT_RECOVERED';
    expect(component.returnIncomplete()).toBeTrue();
    expect(component.returnMissingFields()).toEqual(['return-note']);

    component.returnForm.note = 'Introuvable dans le service';
    component.submitReturn();

    expect(tx.recordUnitReturn).toHaveBeenCalledWith('u1', {
      outcome: 'NOT_RECOVERED',
      coldChainKept: undefined,
      bagIntact: undefined,
      note: 'Introuvable dans le service',
    });
    expect(toast.success).toHaveBeenCalledWith('TRANSFUSION.RETURN.DONE_OUT');
  });

  it('the return dialog shows the two statements only for a bag going back into stock', () => {
    setUp(['ROLE_LAB_SCIENTIST']);
    component.openReturn(unit());
    fixture.detectChanges();
    expect(q('unit-return-dialog')).not.toBeNull();
    expect(q('return-cold-chain')).toBeNull();

    component.returnForm.outcome = 'FIT_FOR_STOCK';
    fixture.detectChanges();
    expect(q('return-cold-chain')).not.toBeNull();
    expect(q('return-intact')).not.toBeNull();

    component.returnForm.outcome = 'UNFIT_DESTROYED';
    fixture.detectChanges();
    expect(q('return-cold-chain')).toBeNull();
    expect((q('return-note') as HTMLTextAreaElement).required).toBeTrue();
  });

  it('a refused return keeps the dialog and shows the server’s reason', () => {
    setUp(['ROLE_LAB_SCIENTIST']);
    tx.recordUnitReturn.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'La poche a déjà été posée.' } })),
    );
    component.openReturn(unit());
    component.returnForm.outcome = 'UNFIT_DESTROYED';
    component.returnForm.note = 'Sceau rompu';

    component.submitReturn();

    expect(toast.error).toHaveBeenCalled();
    expect(component.returningUnit()).not.toBeNull();
    expect(component.returnBusy()).toBeFalse();
  });

  it('cancelling a request with an issued bag says that the bag is still out', () => {
    setUp(['ROLE_DOCTOR']);
    for (const [units, key] of [
      [[unit({ awaitingReturn: true })], 'TRANSFUSION.RETURN.BAGS_OUT_ONE'],
      [
        [unit({ id: 'a', awaitingReturn: true }), unit({ id: 'b', awaitingReturn: true })],
        'TRANSFUSION.RETURN.BAGS_OUT_MANY',
      ],
    ] as const) {
      toast.warning.calls.reset();
      tx.cancelRequest.and.returnValue(of(request({ status: 'CANCELLED', units: [...units] })));
      component.cancelRequest(request());
      fixture.detectChanges();
      answerReasonDialog(fixture, 'Saignement maîtrisé');

      expect(toast.warning).toHaveBeenCalledOnceWith(key);
    }
  });

  it('cancelling a request whose bags never left the laboratory raises no such warning', () => {
    setUp(['ROLE_DOCTOR']);
    tx.cancelRequest.and.returnValue(
      of(request({ status: 'CANCELLED', units: [unit({ status: 'RETURNED' })] })),
    );
    component.cancelRequest(request());
    fixture.detectChanges();
    answerReasonDialog(fixture, 'Saignement maîtrisé');

    expect(toast.warning).not.toHaveBeenCalled();
  });

  it('the return dialog fits at 1440, 768 and 390 px wide', () => {
    setUp(['ROLE_LAB_SCIENTIST']);
    component.openReturn(unit({ unitNumber: 'UNIT-R81-L-OP4-TRES-LONG-NUMERO-DE-POCHE' }));
    component.returnForm.outcome = 'FIT_FOR_STOCK';
    fixture.detectChanges();
    const modal = q('unit-return-dialog')!;
    for (const width of [1440, 768, 390]) {
      modal.style.width = `${Math.min(width - 32, 560)}px`;
      modal.style.maxWidth = `${width - 32}px`;
      fixture.detectChanges();
      expect(modal.scrollWidth)
        .withContext(`return dialog at ${width}px`)
        .toBeLessThanOrEqual(modal.clientWidth + 1);
    }
  });
});
