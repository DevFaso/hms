import { expectKeyboardModal } from '../testing/modal-dialog-keyboard';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import {
  UltrasoundTabComponent,
  hasUltrasoundFinding,
  normalContradictsObservation,
  noFetusContradictsObservation,
} from './ultrasound-tab';
import {
  UltrasoundOrderResponse,
  UltrasoundReportResponse,
  UltrasoundService,
} from '../services/ultrasound.service';
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
      'createOrder',
      'updateOrder',
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

  it('QA D1447: a past date and a scan count below 1 are refused on the field, and the time is a time input', () => {
    create(['ROLE_MIDWIFE']);
    const cmp = fixture.componentInstance;
    const sent = service.createOrder.and.returnValue(of(order()) as never);
    (cmp as unknown as { hospitalId: string }).hospitalId = 'h1';
    cmp.openCreateOrder();
    fixture.detectChanges();
    expect(host().querySelector<HTMLInputElement>('#us-time')?.type).toBe('time');
    expect(host().querySelector<HTMLInputElement>('#us-date')?.min).toBe(cmp.orderDateMin());

    cmp.orderForm.patientId = 'p1';
    cmp.orderForm.scheduledDate = '2026-09-01';
    cmp.submitOrder();
    expect(sent).not.toHaveBeenCalled();
    expect(host().querySelector('#us-date')?.getAttribute('aria-invalid')).toBe('true');

    cmp.orderForm.scheduledDate = undefined;
    cmp.orderForm.scanCountForPregnancy = -1;
    cmp.submitOrder();
    expect(sent).not.toHaveBeenCalled();
    expect(host().querySelector('#us-scan-count')?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('#us-date')?.getAttribute('aria-invalid')).not.toBe('true');

    cmp.orderForm.scanCountForPregnancy = 2;
    cmp.submitOrder();
    expect(sent).toHaveBeenCalledTimes(1);
  });

  it('QA D1447: an order whose date has passed stays editable on its other fields', () => {
    create(['ROLE_MIDWIFE']);
    const cmp = fixture.componentInstance;
    const sent = service.updateOrder.and.returnValue(of(order()) as never);
    (cmp as unknown as { hospitalId: string }).hospitalId = 'h1';
    cmp.openEditOrder(
      order({
        scheduledDate: '2026-09-01',
        scanCountForPregnancy: -1,
        scheduledTime: '99:99 demain',
      }),
    );
    fixture.detectChanges();
    expect(cmp.orderDateMin()).toBe('2026-09-01');
    cmp.orderForm.clinicalIndication = 'contrôle';
    cmp.submitOrder();
    expect(sent).toHaveBeenCalledTimes(1);
    expect(sent.calls.mostRecent().args[1].scheduledTime).toBe('99:99 demain');
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
    // QA D1525: an empty report is refused first, as on the server.
    save();
    expect(service.submitReport).not.toHaveBeenCalled();
    expect(host().querySelector('#rep-summary')?.getAttribute('aria-invalid')).toBe('true');

    // A finding category alone is not a report: nothing was measured or written.
    cmp.reportForm.findingCategory = 'NORMAL';
    save();
    expect(service.submitReport).not.toHaveBeenCalled();
    expect(host().querySelector('#rep-summary')?.getAttribute('aria-invalid')).toBe('true');

    // A finding without a category: the category is named, never assumed.
    cmp.reportForm.findingCategory = '';
    cmp.reportForm.fetalHeartRate = 142;
    save();
    expect(service.submitReport).not.toHaveBeenCalled();
    expect(host().querySelector('#rep-category')?.getAttribute('aria-invalid')).toBe('true');
    expect(host().querySelector('#rep-summary')?.getAttribute('aria-invalid')).toBeNull();

    service.submitReport.and.returnValue(of({}) as never);
    cmp.reportForm.findingCategory = 'NORMAL';
    save();
    expect(service.submitReport).toHaveBeenCalledWith(
      'us-1',
      jasmine.objectContaining({ findingCategory: 'NORMAL', fetalHeartRate: 142 }),
    );
  });

  // QA D1182 (round 22): editing a blank stored report re-sent the server's
  // defaults (cardiac activity yes, one fetus) and saved a blank "Normal".
  // QA D1525 review: the server reads a legacy row's possible defaults as
  // unknown (null) and keeps a typed FALSE; the form loads exactly that, so a
  // default is never re-sent and a typed "absent" is never erased.
  it('QA D1182/D1525: editing a blank legacy report is refused, its unknowns stay unknown and a typed FALSE stays', () => {
    create(['ROLE_MIDWIFE']);
    const cmp = fixture.componentInstance;
    const stored = {
      id: 'rep-1',
      ultrasoundOrderId: 'us-1',
      scanDate: '2026-11-01',
      fetalMovementObserved: false,
      observationsStated: false,
    };
    cmp.openReport({ ...order(), report: stored } as never);
    fixture.detectChanges();
    expect(cmp.reportForm.findingCategory).toBe('');
    expect(cmp.reportForm.fetalCardiacActivity).toBeNull();
    expect(cmp.reportForm.fetalMovementObserved).withContext('typed, kept').toBeFalse();
    expect(cmp.reportForm.fetalToneNormal).toBeNull();
    expect(cmp.reportForm.anomaliesDetected).toBeNull();
    expect(cmp.reportForm.numberOfFetuses).toBeUndefined();
    cmp.reportForm.fetalMovementObserved = null;
    const save = () => {
      const buttons = Array.from(
        host().querySelectorAll<HTMLButtonElement>('.modal-actions .btn-primary'),
      );
      buttons[buttons.length - 1].click();
      fixture.detectChanges();
    };
    save();
    expect(service.submitReport).not.toHaveBeenCalled();
    expect(host().querySelector('#rep-summary')?.getAttribute('aria-invalid')).toBe('true');

    service.submitReport.and.returnValue(of({}) as never);
    cmp.reportForm.findingsSummary = 'Grossesse évolutive, activité cardiaque visible';
    // The legacy NORMAL read as unknown: the clinician chooses the category.
    cmp.reportForm.findingCategory = 'NORMAL';
    save();
    const sent = service.submitReport.calls.mostRecent().args[1] as unknown as Record<
      string,
      unknown
    >;
    expect(sent['findingsSummary']).toContain('activité cardiaque');
    expect(sent['fetalCardiacActivity']).toBeNull();
    expect(sent['fetalMovementObserved']).toBeNull();
    expect(sent['fetalToneNormal']).toBeNull();
    expect(sent['anomaliesDetected']).toBeNull();
    expect(sent['numberOfFetuses']).toBeUndefined();
    expect(sent['observationsStated'])
      .withContext('this client states its observations')
      .toBeTrue();
  });

  it('QA D1525: a stated observation is shown, and "not assessed" is sent as null', () => {
    create(['ROLE_MIDWIFE']);
    const cmp = fixture.componentInstance;
    cmp.openReport({
      ...order(),
      report: {
        id: 'rep-1',
        scanDate: '2026-11-01',
        findingCategory: 'NORMAL',
        findingsSummary: 'Grossesse évolutive',
        fetalCardiacActivity: true,
        fetalMovementObserved: true,
        numberOfFetuses: 1,
        observationsStated: true,
      },
    } as never);
    fixture.detectChanges();
    expect(cmp.reportForm.fetalCardiacActivity).toBeTrue();
    expect(cmp.reportForm.fetalToneNormal).toBeNull();
    expect(cmp.reportForm.numberOfFetuses).toBe(1);
    service.submitReport.and.returnValue(of({}) as never);
    cmp.reportForm.fetalMovementObserved = null;
    cmp.submitReport();
    const sent = service.submitReport.calls.mostRecent().args[1];
    expect(sent.fetalCardiacActivity).toBeTrue();
    expect(sent.fetalMovementObserved).toBeNull();
    expect(sent.fetalToneNormal).toBeNull();
    expect('observationsStated' in sent)
      .withContext('a stated report needs no flag')
      .toBeFalse();
  });

  describe('QA D1525: a finding is charted only when the clinician states it', () => {
    const save = () => {
      const buttons = Array.from(
        host().querySelectorAll<HTMLButtonElement>('.modal-actions .btn-primary'),
      );
      buttons[buttons.length - 1].click();
      fixture.detectChanges();
    };

    it('preselects no category and leaves every observation not assessed', () => {
      create(['ROLE_MIDWIFE']);
      const form = fixture.componentInstance.emptyReportForm();
      expect(form.findingCategory).toBe('');
      expect(form.fetalCardiacActivity).toBeNull();
      expect(form.fetalMovementObserved).toBeNull();
      expect(form.fetalToneNormal).toBeNull();
      expect(form.anomaliesDetected).toBeNull();
    });

    it('offers no template button: a template stated findings, or overwrote the scan date', () => {
      create(['ROLE_MIDWIFE']);
      fixture.componentInstance.openReport(order());
      fixture.detectChanges();
      expect(host().querySelector('.template-buttons')).toBeNull();
    });

    it('refuses a report whose every observation is not assessed, before asking for the category', () => {
      create(['ROLE_MIDWIFE']);
      const cmp = fixture.componentInstance;
      cmp.openReport(order());
      fixture.detectChanges();
      save();
      expect(service.submitReport).not.toHaveBeenCalled();
      expect(host().querySelector('#rep-summary')?.getAttribute('aria-invalid')).toBe('true');
      expect(host().querySelector('#rep-category')?.getAttribute('aria-invalid')).toBeNull();
    });

    it('saves "cardiac activity: absent" with URGENT and nothing else', () => {
      create(['ROLE_MIDWIFE']);
      const cmp = fixture.componentInstance;
      service.submitReport.and.returnValue(of({ id: 'r-1' } as UltrasoundReportResponse));
      cmp.openReport(order());
      fixture.detectChanges();
      cmp.reportForm.fetalCardiacActivity = false;
      cmp.reportForm.findingCategory = 'URGENT';
      save();
      const sent = service.submitReport.calls.mostRecent().args[1];
      expect(sent.fetalCardiacActivity).toBeFalse();
      expect(sent.fetalMovementObserved).toBeNull();
      expect(sent.findingCategory).toBe('URGENT');
      expect('observationsStated' in sent)
        .withContext('a new report needs no flag')
        .toBeFalse();
    });

    it('refuses NORMAL with absent cardiac activity, abnormal tone, anomalies or an absent nasal bone, on the category', () => {
      create(['ROLE_MIDWIFE']);
      const cmp = fixture.componentInstance;
      cmp.openReport(order());
      fixture.detectChanges();
      for (const contradiction of [
        { fetalCardiacActivity: false },
        { fetalToneNormal: false },
        { anomaliesDetected: true, anomalyDescription: 'Hygroma' },
        { nasalBonePresent: false, nuchalTranslucencyMm: 3.5 },
      ]) {
        cmp.reportForm = {
          ...cmp.emptyReportForm(),
          findingCategory: 'NORMAL',
          ...contradiction,
        };
        fixture.detectChanges();
        save();
        expect(service.submitReport)
          .withContext(JSON.stringify(contradiction))
          .not.toHaveBeenCalled();
        expect(host().querySelector('#rep-category')?.getAttribute('aria-invalid'))
          .withContext(JSON.stringify(contradiction))
          .toBe('true');
      }
    });

    it('renders the observations and the anomalies answer as tri-state selects', () => {
      create(['ROLE_MIDWIFE']);
      fixture.componentInstance.openReport(order());
      fixture.detectChanges();
      for (const id of [
        'fetalCardiacActivity',
        'fetalMovementObserved',
        'fetalToneNormal',
        'nasal-bone',
        'anomalies',
      ]) {
        const select = host().querySelector<HTMLSelectElement>(`#rep-${id}`);
        expect(select).withContext(id).not.toBeNull();
        expect(select!.options.length).withContext(id).toBe(3);
      }
    });

    it('shows every observation in the report view, "not assessed" and legacy included', () => {
      const viewed = (report: Partial<UltrasoundReportResponse>): string[] => {
        create(['ROLE_DOCTOR']);
        fixture.componentInstance.openOrder(
          order({
            status: 'IN_PROGRESS',
            report: { id: 'r-1', ...report } as UltrasoundReportResponse,
          }),
        );
        fixture.detectChanges();
        return texts('[data-testid="ultrasound-observations"] li');
      };
      const stated = viewed({
        observationsStated: true,
        fetalCardiacActivity: false,
        fetalToneNormal: true,
      });
      expect(stated.length).toBe(6);
      expect(stated[0]).toContain('ULTRASOUND.FLAG_CARDIAC_ABSENT');
      expect(stated[1]).toContain('ULTRASOUND.FLAG_MOVEMENT_NOT_ASSESSED');
      expect(stated[2]).toContain('ULTRASOUND.FLAG_TONE_NORMAL');
      expect(stated[3]).toContain('ULTRASOUND.NASAL_BONE_NOT_ASSESSED');
      expect(stated[4]).toContain('ULTRASOUND.ANOMALIES_NOT_ASSESSED');
      expect(stated[5]).toContain('ULTRASOUND.FETUSES_NOT_COUNTED');
    });

    it('reads a stated report with every observation and the fetus count null as not assessed', () => {
      create(['ROLE_DOCTOR']);
      fixture.componentInstance.openOrder(
        order({
          status: 'IN_PROGRESS',
          report: {
            id: 'r-1',
            observationsStated: true,
            fetalCardiacActivity: null,
            fetalMovementObserved: null,
            fetalToneNormal: null,
            numberOfFetuses: undefined,
          } as UltrasoundReportResponse,
        }),
      );
      fixture.detectChanges();
      const items = texts('[data-testid="ultrasound-observations"] li');
      expect(items).toEqual([
        jasmine.stringContaining('ULTRASOUND.FLAG_CARDIAC_NOT_ASSESSED'),
        jasmine.stringContaining('ULTRASOUND.FLAG_MOVEMENT_NOT_ASSESSED'),
        jasmine.stringContaining('ULTRASOUND.FLAG_TONE_NOT_ASSESSED'),
        jasmine.stringContaining('ULTRASOUND.NASAL_BONE_NOT_ASSESSED'),
        jasmine.stringContaining('ULTRASOUND.ANOMALIES_NOT_ASSESSED'),
        jasmine.stringContaining('ULTRASOUND.FETUSES_NOT_COUNTED'),
      ]);
      for (const item of items) {
        expect(item).not.toContain('ABSENT');
        expect(item).not.toContain('ABNORMAL');
      }
    });

    it('reads a legacy report’s unknowns as "no answer (legacy)" and keeps its typed answers', () => {
      create(['ROLE_DOCTOR']);
      fixture.componentInstance.openOrder(
        order({
          status: 'IN_PROGRESS',
          report: {
            id: 'r-1',
            fetalToneNormal: false,
            anomaliesDetected: true,
            numberOfFetuses: 2,
            observationsStated: false,
          } as UltrasoundReportResponse,
        }),
      );
      fixture.detectChanges();
      const items = texts('[data-testid="ultrasound-observations"] li');
      expect(items).toEqual([
        jasmine.stringContaining('ULTRASOUND.FLAG_NOT_STATED_LEGACY'),
        jasmine.stringContaining('ULTRASOUND.FLAG_NOT_STATED_LEGACY'),
        jasmine.stringContaining('ULTRASOUND.FLAG_TONE_ABNORMAL'),
        jasmine.stringContaining('ULTRASOUND.FLAG_NOT_STATED_LEGACY'),
        jasmine.stringContaining('ULTRASOUND.ANOMALIES_YES'),
        jasmine.stringContaining('2'),
      ]);
    });

    it('refuses a negative or implausible fetus count on the field, and takes 0 as no fetus seen', () => {
      create(['ROLE_MIDWIFE']);
      const cmp = fixture.componentInstance;
      service.submitReport.and.returnValue(of({ id: 'r-1' } as UltrasoundReportResponse));
      cmp.openReport(order());
      fixture.detectChanges();
      for (const bad of [-1, 9, 1.5]) {
        cmp.reportForm = {
          ...cmp.emptyReportForm(),
          findingCategory: 'URGENT',
          numberOfFetuses: bad,
        };
        fixture.detectChanges();
        save();
        expect(service.submitReport).withContext(String(bad)).not.toHaveBeenCalled();
        expect(host().querySelector('#rep-fetuses')?.getAttribute('aria-invalid'))
          .withContext(String(bad))
          .toBe('true');
      }
      cmp.reportForm = { ...cmp.emptyReportForm(), findingCategory: 'URGENT', numberOfFetuses: 0 };
      fixture.detectChanges();
      save();
      expect(service.submitReport.calls.mostRecent().args[1].numberOfFetuses).toBe(0);
    });

    it('counts a stated answer as a finding, true or false, and nothing when every answer is unstated', () => {
      expect(
        hasUltrasoundFinding({
          fetalCardiacActivity: null,
          fetalMovementObserved: null,
          fetalToneNormal: null,
          anomaliesDetected: null,
          findingCategory: 'NORMAL',
          findingsSummary: '  ',
        }),
      ).toBeFalse();
      expect(hasUltrasoundFinding({ fetalCardiacActivity: false })).toBeTrue();
      expect(hasUltrasoundFinding({ fetalToneNormal: true })).toBeTrue();
      expect(hasUltrasoundFinding({ anomaliesDetected: false })).toBeTrue();
      expect(hasUltrasoundFinding({ numberOfFetuses: 1 })).toBeTrue();
      expect(hasUltrasoundFinding({ numberOfFetuses: 0 }))
        .withContext('no fetus seen')
        .toBeTrue();
      expect(hasUltrasoundFinding({ fetalHeartRate: 150 })).toBeTrue();
      expect(hasUltrasoundFinding({ findingsSummary: 'Grossesse évolutive' })).toBeTrue();
    });

    it('refuses no fetus seen with a present heart or movement on the fetus count, and with NORMAL on the category', () => {
      create(['ROLE_MIDWIFE']);
      const cmp = fixture.componentInstance;
      cmp.openReport(order());
      fixture.detectChanges();
      for (const present of [{ fetalCardiacActivity: true }, { fetalMovementObserved: true }]) {
        cmp.reportForm = {
          ...cmp.emptyReportForm(),
          findingCategory: 'URGENT',
          numberOfFetuses: 0,
          ...present,
        };
        fixture.detectChanges();
        save();
        expect(service.submitReport).withContext(JSON.stringify(present)).not.toHaveBeenCalled();
        expect(host().querySelector('#rep-fetuses')?.getAttribute('aria-invalid'))
          .withContext(JSON.stringify(present))
          .toBe('true');
      }
      cmp.reportForm = { ...cmp.emptyReportForm(), findingCategory: 'NORMAL', numberOfFetuses: 0 };
      fixture.detectChanges();
      save();
      expect(service.submitReport).not.toHaveBeenCalled();
      expect(host().querySelector('#rep-category')?.getAttribute('aria-invalid')).toBe('true');
      expect(
        noFetusContradictsObservation({ numberOfFetuses: 0, fetalCardiacActivity: false }),
      ).toBeFalse();
    });

    it('NORMAL contradicts only a stated absent, abnormal, anomaly or absent-nasal-bone answer', () => {
      expect(
        normalContradictsObservation({ findingCategory: 'NORMAL', numberOfFetuses: 0 }),
      ).toBeTrue();
      expect(
        normalContradictsObservation({ findingCategory: 'NORMAL', nasalBonePresent: false }),
      ).toBeTrue();
      expect(
        normalContradictsObservation({ findingCategory: 'NORMAL', fetalMovementObserved: false }),
      )
        .withContext('absent movement can be normal on a single scan')
        .toBeFalse();
      expect(
        normalContradictsObservation({ findingCategory: 'NORMAL', fetalCardiacActivity: false }),
      ).toBeTrue();
      expect(
        normalContradictsObservation({ findingCategory: 'URGENT', fetalCardiacActivity: false }),
      ).toBeFalse();
      expect(
        normalContradictsObservation({
          findingCategory: 'NORMAL',
          fetalCardiacActivity: null,
          fetalToneNormal: true,
        }),
      ).toBeFalse();
    });
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
