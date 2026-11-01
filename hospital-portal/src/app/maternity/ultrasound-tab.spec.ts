import { expectKeyboardModal } from '../testing/modal-dialog-keyboard';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { UltrasoundTabComponent } from './ultrasound-tab';
import { UltrasoundOrderResponse, UltrasoundService } from '../services/ultrasound.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { RoleContextStub, roleContextStub } from '../testing/role-context.stub';

/**
 * The ultrasound worklists of the maternity page. Asserted through the rendered
 * DOM in French (the #661 shape): scan type and status render through a key
 * built in the template, so only the DOM shows whether the key resolves.
 */
describe('UltrasoundTabComponent', () => {
  let fixture: ComponentFixture<UltrasoundTabComponent>;
  let service: jasmine.SpyObj<UltrasoundService>;
  let toast: jasmine.SpyObj<ToastService>;
  let roleCtx: RoleContextStub;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');
  const actionTitles = (): (string | null)[] =>
    Array.from(host().querySelectorAll('.actions-cell button')).map((b) => b.getAttribute('title'));

  function order(overrides: Partial<UltrasoundOrderResponse> = {}): UltrasoundOrderResponse {
    return {
      id: 'us-1',
      patientId: 'p-1',
      patientDisplayName: 'Awa Traoré',
      hospitalId: 'h-1',
      scanType: 'ANATOMY_SCAN',
      status: 'ORDERED',
      gestationalAgeAtOrder: 20,
      ...overrides,
    };
  }

  function create(roles: string[], orders: UltrasoundOrderResponse[] = [order()]): void {
    service.ordersByHospital.and.returnValue(of(orders));
    roleCtx.set({ roles });
    fixture = TestBed.createComponent(UltrasoundTabComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<UltrasoundService>('UltrasoundService', [
      'ordersByHospital',
      'pendingOrders',
      'highRiskOrders',
      'followUpRequired',
      'anomalies',
      'cancelOrder',
      'submitReport',
    ]);
    service.pendingOrders.and.returnValue(of([]));
    service.anomalies.and.returnValue(of([]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    roleCtx = roleContextStub({ superAdmin: false, hospitalId: 'h-1', roles: [] });
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['getHospitalId']);

    TestBed.configureTestingModule({
      imports: [UltrasoundTabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: UltrasoundService, useValue: service },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      ULTRASOUND: {
        WORKLIST_ALL: 'Toutes',
        WORKLIST_PENDING: 'En attente',
        WORKLIST_ANOMALIES: 'Anomalies',
        TYPE_ANATOMY_SCAN: 'Échographie morphologique',
        STATUS_ORDERED: 'Prescrite',
        NO_ORDERS: 'Aucune demande',
        NO_REPORTS: 'Aucun compte rendu',
        LOAD_ERROR: 'Chargement impossible',
      },
      PORTAL: {
        ENUM: {
          IMAGING_ORDER_PRIORITY: {
            ROUTINE: 'Routine',
            URGENT: 'Urgente',
            STAT: 'Immédiat (STAT)',
          },
        },
      },
    });
  });

  it('QA D1117: the priority reads in French in the list, the view and the order form', () => {
    create(['ROLE_MIDWIFE'], [order({ priority: 'STAT' })]);
    expect(texts('tbody td')).toContain('Immédiat (STAT)');
    expect(host().textContent).not.toContain('STAT ');

    fixture.componentInstance.openOrder(order({ priority: 'URGENT' }));
    fixture.detectChanges();
    expect(host().textContent).toContain('Urgente');

    fixture.componentInstance.openCreateOrder();
    fixture.detectChanges();
    const options = Array.from(host().querySelectorAll('#us-priority option')).map((o) =>
      o.textContent?.trim(),
    );
    expect(options).toEqual(['Routine', 'Urgente', 'Immédiat (STAT)']);
  });

  it('QA D1144: the cancel prompt takes the keyboard, and gives it back to "Cancel the order"', fakeAsync(() => {
    create(['ROLE_MIDWIFE']);
    const cmp = fixture.componentInstance;
    spyOn(cmp, 'canCancelThisOrder').and.returnValue(true);
    document.body.appendChild(host());
    try {
      cmp.openOrder(order());
      fixture.detectChanges();
      host().querySelector<HTMLButtonElement>('[data-testid="us-cancel-order"]')!.click();
      fixture.detectChanges();
      tick();
      expect(document.activeElement?.id).toBe('us-cancel-reason');

      cmp.closeCancelPrompt();
      fixture.detectChanges();
      tick();
      expect(document.activeElement).toBe(host().querySelector('[data-testid="us-cancel-order"]'));

      // Once cancelled there is no "Cancel" to return to: the dialog's Close takes the keyboard.
      service.cancelOrder.and.returnValue(of(order({ status: 'CANCELLED' })));
      cmp.openCancelPrompt();
      fixture.detectChanges();
      tick();
      cmp.cancelReason = 'Doublon';
      cmp.submitCancel();
      fixture.detectChanges();
      tick();
      expect(document.activeElement).toBe(host().querySelector('[data-testid="us-order-close"]'));
    } finally {
      host().remove();
    }
  }));

  it('QA D1155: Escape in the cancel prompt closes only the prompt, and asks before dropping a typed reason', fakeAsync(() => {
    create(['ROLE_MIDWIFE']);
    const cmp = fixture.componentInstance;
    spyOn(cmp, 'canCancelThisOrder').and.returnValue(true);
    document.body.appendChild(host());
    const escape = (target: Element) => {
      target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();
      tick();
    };
    const reason = () => host().querySelector<HTMLTextAreaElement>('#us-cancel-reason');
    try {
      cmp.openOrder(order());
      fixture.detectChanges();
      host().querySelector<HTMLButtonElement>('[data-testid="us-cancel-order"]')!.click();
      fixture.detectChanges();
      tick();
      expect(document.activeElement).toBe(reason());

      reason()!.value = 'Doublon';
      reason()!.dispatchEvent(new Event('input'));
      escape(reason()!);
      // The view dialog is still open, the text is still there, and the prompt asks.
      expect(cmp.viewedOrder()).not.toBeNull();
      expect(reason()!.value).toBe('Doublon');
      expect(host().querySelector('[data-testid="inline-discard-ask"]')).not.toBeNull();
      expect(document.activeElement).toBe(host().querySelector('[data-testid="inline-keep"]'));

      // Escape on the question goes back to the text.
      escape(document.activeElement!);
      expect(host().querySelector('[data-testid="inline-discard-ask"]')).toBeNull();
      expect(document.activeElement).toBe(reason());

      // Discarding closes only the prompt; the keyboard goes back to "Cancel the order".
      escape(reason()!);
      host().querySelector<HTMLButtonElement>('[data-testid="inline-discard"]')!.click();
      fixture.detectChanges();
      tick();
      expect(cmp.showCancelPrompt()).toBeFalse();
      expect(cmp.viewedOrder()).not.toBeNull();
      expect(document.activeElement).toBe(host().querySelector('[data-testid="us-cancel-order"]'));

      // An empty prompt closes on Escape at once, still leaving the dialog open.
      host().querySelector<HTMLButtonElement>('[data-testid="us-cancel-order"]')!.click();
      fixture.detectChanges();
      tick();
      escape(reason()!);
      expect(cmp.showCancelPrompt()).toBeFalse();
      expect(cmp.viewedOrder()).not.toBeNull();
      expect(document.activeElement).toBe(host().querySelector('[data-testid="us-cancel-order"]'));
    } finally {
      host().remove();
    }
  }));

  it('QA D1150: the finding is never preselected and a report with no finding is refused, field by field', () => {
    create(['ROLE_MIDWIFE']);
    const cmp = fixture.componentInstance;
    cmp.openReport(order());
    fixture.detectChanges();
    expect(cmp.reportForm.findingCategory).toBe('');
    const save = () => {
      const buttons = Array.from(
        host().querySelectorAll<HTMLButtonElement>('.modal-actions .btn-primary'),
      );
      buttons[buttons.length - 1].click();
      fixture.detectChanges();
    };
    save();
    expect(service.submitReport).not.toHaveBeenCalled();
    expect(host().querySelector('#rep-category')?.getAttribute('aria-invalid')).toBe('true');

    // A finding category alone is not a report: nothing was measured or written.
    cmp.reportForm.findingCategory = 'NORMAL';
    save();
    expect(service.submitReport).not.toHaveBeenCalled();
    expect(host().querySelector('#rep-category')?.getAttribute('aria-invalid')).toBeNull();
    expect(host().querySelector('#rep-summary')?.getAttribute('aria-invalid')).toBe('true');

    service.submitReport.and.returnValue(of({}) as never);
    cmp.reportForm.fetalHeartRate = 142;
    save();
    expect(service.submitReport).toHaveBeenCalledWith(
      'us-1',
      jasmine.objectContaining({ findingCategory: 'NORMAL', fetalHeartRate: 142 }),
    );
  });

  it('lists the hospital’s orders with a translated scan type and status', () => {
    create(['ROLE_MIDWIFE']);
    expect(service.ordersByHospital).toHaveBeenCalledWith('h-1');
    const cells = texts('tbody td');
    expect(cells[0]).toBe('Awa Traoré');
    expect(cells[1]).toBe('Échographie morphologique');
    expect(texts('tbody .status-badge')).toEqual(['Prescrite']);
  });

  it('renders the worklist tabs translated', () => {
    create(['ROLE_MIDWIFE']);
    const tabs = texts('.tab-btn');
    expect(tabs[0]).toBe('Toutes');
    expect(tabs[1]).toBe('En attente');
    expect(tabs[4]).toBe('Anomalies');
  });

  it('gives the ordering roles edit and report, and nobody writes a cancelled order', () => {
    create(['ROLE_MIDWIFE'], [order(), order({ id: 'us-2', status: 'CANCELLED' })]);
    expect(host().querySelector('.filter-bar .btn-primary')).not.toBeNull();
    expect(actionTitles()).toEqual([
      'COMMON.VIEW',
      'COMMON.EDIT',
      'ULTRASOUND.RECORD_REPORT',
      'COMMON.VIEW',
    ]);
  });

  it('offers a nurse the list but no write control', () => {
    create(['ROLE_NURSE']);
    expect(host().querySelector('.filter-bar .btn-primary')).toBeNull();
    expect(actionTitles()).toEqual(['COMMON.VIEW']);
  });

  it('switches to a report worklist and shows its translated empty state', () => {
    create(['ROLE_DOCTOR']);
    const anomalies = Array.from(host().querySelectorAll('.tab-btn'))[4] as HTMLElement;
    anomalies.click();
    fixture.detectChanges();
    expect(service.anomalies).toHaveBeenCalledWith('h-1');
    expect(texts('.empty-state h3')).toEqual(['Aucun compte rendu']);
  });

  it('shows the translated empty state for an empty order list', () => {
    create(['ROLE_DOCTOR'], []);
    expect(texts('.empty-state h3')).toEqual(['Aucune demande']);
  });

  it('toasts the translated error when the list cannot load', () => {
    service.ordersByHospital.and.returnValue(throwError(() => new Error('500')));
    fixture = TestBed.createComponent(UltrasoundTabComponent);
    fixture.detectChanges();
    expect(toast.error).toHaveBeenCalledWith('Chargement impossible');
    expect(host().querySelector('.loading-state')).toBeNull();
  });

  describe('keyboard modals (QA D1004)', () => {
    afterEach(() => (fixture.nativeElement as HTMLElement).remove());

    it('"Nouvelle demande d’échographie": focus moves in, Escape closes, focus returns', () => {
      create(['ROLE_MIDWIFE']);
      document.body.appendChild(fixture.nativeElement);
      const opener = host().querySelector<HTMLElement>('.btn-primary')!;
      expectKeyboardModal(fixture, opener);
    });

    it('the view-échographie dialog: focus moves in, Escape closes, focus returns', () => {
      create(['ROLE_DOCTOR']);
      document.body.appendChild(fixture.nativeElement);
      const opener = host().querySelector<HTMLElement>('.actions-cell button')!;
      expectKeyboardModal(fixture, opener);
    });
  });
});
