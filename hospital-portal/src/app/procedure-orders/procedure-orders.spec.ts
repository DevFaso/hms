import { TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ProcedureOrdersComponent } from './procedure-orders';
import { ProcedureOrderResponse, ProcedureOrderService } from '../services/procedure-order.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

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
    expect(component.minScheduleDatetime).toBe('2026-10-26T10:00');
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
});
