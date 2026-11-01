import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ProcedureOrdersComponent } from './procedure-orders';
import { ProcedureOrderResponse, ProcedureOrderService } from '../services/procedure-order.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { roleContextStub } from '../testing/role-context.stub';

const order: ProcedureOrderResponse = {
  id: 'o1',
  patientId: 'p1',
  hospitalId: 'h1',
  procedureName: 'Appendectomy',
  indication: 'RLQ pain',
  urgency: 'URGENT',
  status: 'ORDERED',
} as ProcedureOrderResponse;

describe('ProcedureOrdersComponent', () => {
  let service: jasmine.SpyObj<ProcedureOrderService>;
  let component: ProcedureOrdersComponent;

  beforeEach(() => {
    service = jasmine.createSpyObj<ProcedureOrderService>('ProcedureOrderService', [
      'update',
      'cancel',
      'byHospital',
      'pendingConsent',
    ]);
    service.update.and.returnValue(of({ ...order, consentObtained: true }));
    TestBed.configureTestingModule({
      providers: [
        { provide: ProcedureOrderService, useValue: service },
        {
          provide: AuthService,
          useValue: { getHospitalId: () => 'h1', getUserProfile: () => null },
        },
        {
          provide: RoleContextService,
          useValue: { activeHospitalId: 'h1', hasAnyActiveRole: () => true },
        },
        { provide: ToastService, useValue: { success: () => undefined, error: () => undefined } },
        { provide: TranslateService, useValue: { instant: (k: string) => k } },
      ],
    });
    component = TestBed.runInInjectionContext(() => new ProcedureOrdersComponent());
    jasmine.clock().install();
  });

  afterEach(() => jasmine.clock().uninstall());

  it('sends the consent time on the server UTC clock, whatever the browser zone (#948 review)', () => {
    // 14:00 UTC. Sent as the browser's wall clock, a New York workstation
    // stored 10:00 and the list (which reads it through serverTime) showed 06:00.
    jasmine.clock().mockDate(new Date(Date.UTC(2026, 9, 24, 14, 0, 0)));
    component.viewedOrder.set(order);
    component.consentBy = 'Dr Test';

    component.submitConsent();

    expect(service.update).toHaveBeenCalledOnceWith(
      'o1',
      jasmine.objectContaining({
        consentObtained: true,
        consentObtainedAt: '2026-10-24T14:00:00',
        consentObtainedBy: 'Dr Test',
      }),
    );
  });

  it('QA D857: a reschedule to 01/01/2020 09:00 is refused before any request', () => {
    jasmine.clock().mockDate(new Date(2026, 9, 26, 10, 0, 0));
    const toast = TestBed.inject(ToastService);
    spyOn(toast, 'error');
    component.viewedOrder.set({ ...order, status: 'SCHEDULED', consentObtained: true });
    component.openAction('schedule');
    component.scheduleDatetime = '2020-01-01T09:00';

    component.submitSchedule();

    expect(service.update).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('PROCEDURES.PAST_DATE');
  });

  it('QA D857: a future reschedule still goes through', () => {
    jasmine.clock().mockDate(new Date(2026, 9, 26, 10, 0, 0));
    component.viewedOrder.set({ ...order, status: 'SCHEDULED', consentObtained: true });
    component.openAction('schedule');
    component.scheduleDatetime = '2026-10-27T09:00';

    component.submitSchedule();

    expect(service.update).toHaveBeenCalledOnceWith(
      'o1',
      jasmine.objectContaining({ status: 'SCHEDULED' }),
    );
  });

  it("QA D857: the server's refusal reason is shown, not the generic error", () => {
    const toast = TestBed.inject(ToastService);
    spyOn(toast, 'error');
    service.update.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          message: "La date et l'heure prévues d'un acte ne peuvent pas être dans le passé.",
        },
      })),
    );
    component.viewedOrder.set(order);

    component.setStatus('SCHEDULED');

    expect(toast.error).toHaveBeenCalledWith(
      "La date et l'heure prévues d'un acte ne peuvent pas être dans le passé.",
    );
  });

  // Product decision 2026-10-28: a colleague's cancel is refused with a 403
  // (the error interceptor leaves it to the page); the server's translated
  // reason is shown and the order stays open.
  it("a refused cancel shows the server's reason and leaves the order as it was", () => {
    const toast = TestBed.inject(ToastService);
    spyOn(toast, 'error');
    service.cancel.and.returnValue(
      throwError(() => ({
        status: 403,
        error: {
          message:
            "Seul le clinicien qui a demandé cet acte peut annuler la demande, ou un médecin de cet établissement lorsqu'elle a été saisie par un infirmier ou une sage-femme.",
        },
      })),
    );
    component.viewedOrder.set(order);
    component.openAction('cancel');
    component.cancelReason = 'Patient declined';

    component.submitCancel();

    expect(service.cancel).toHaveBeenCalledOnceWith('o1', 'Patient declined');
    expect(toast.error).toHaveBeenCalledWith(
      "Seul le clinicien qui a demandé cet acte peut annuler la demande, ou un médecin de cet établissement lorsqu'elle a été saisie par un infirmier ou une sage-femme.",
    );
    expect(component.viewedOrder()?.status).toBe('ORDERED');
    expect(component.actionBusy()).toBeFalse();
  });
});

/**
 * QA D1155 sibling (round 21): the order dialog's schedule / consent / cancel
 * steps are inline forms; Escape in one closed the whole dialog with the
 * typed text, and closing a step dropped the keyboard on the page body (its
 * opener is hidden while the step is open).
 */
describe('ProcedureOrdersComponent — inline steps keep Escape (QA D1155)', () => {
  let fixture: ComponentFixture<ProcedureOrdersComponent>;
  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const escape = (target: Element) => {
    target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    tick();
  };

  beforeEach(() => {
    const service = jasmine.createSpyObj<ProcedureOrderService>('ProcedureOrderService', [
      'byHospital',
      'pendingConsent',
    ]);
    service.byHospital.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [ProcedureOrdersComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ProcedureOrderService, useValue: service },
        {
          provide: AuthService,
          useValue: { getHospitalId: () => 'h1', getUserProfile: () => null },
        },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
        { provide: ToastService, useValue: { success: () => undefined, error: () => undefined } },
      ],
    });
    fixture = TestBed.createComponent(ProcedureOrdersComponent);
    fixture.detectChanges();
    document.body.appendChild(host());
  });

  afterEach(() => host().remove());

  it('the cancel step: Escape closes only the step, asks over a typed reason, and the keyboard returns to its button', fakeAsync(() => {
    const cmp = fixture.componentInstance;
    spyOn(cmp, 'canCancelOrder').and.returnValue(true);
    cmp.openOrder(order);
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-proc-action="cancel"]')!.click();
    fixture.detectChanges();
    tick();
    const reason = host().querySelector<HTMLTextAreaElement>('#proc-cancel-reason')!;
    expect(document.activeElement).toBe(reason);

    reason.value = 'Le patient refuse';
    reason.dispatchEvent(new Event('input'));
    escape(reason);
    expect(cmp.viewedOrder()).not.toBeNull();
    expect(cmp.detailAction()).toBe('cancel');
    expect(document.activeElement).toBe(host().querySelector('[data-testid="inline-keep"]'));

    host().querySelector<HTMLButtonElement>('[data-testid="inline-discard"]')!.click();
    fixture.detectChanges();
    tick();
    expect(cmp.detailAction()).toBeNull();
    expect(cmp.viewedOrder()).not.toBeNull();
    expect(document.activeElement).toBe(host().querySelector('[data-proc-action="cancel"]'));
  }));

  it('the consent step: a prefilled name is not "typed"; Escape closes the step at once', fakeAsync(() => {
    const cmp = fixture.componentInstance;
    cmp.openOrder(order);
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-proc-action="consent"]')!.click();
    fixture.detectChanges();
    tick();
    escape(host().querySelector('#proc-consent-by')!);
    expect(cmp.detailAction()).toBeNull();
    expect(cmp.viewedOrder()).not.toBeNull();
    expect(document.activeElement).toBe(host().querySelector('[data-proc-action="consent"]'));
  }));
});
