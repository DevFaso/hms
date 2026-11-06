import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { OrderSetPickerComponent } from './order-set-picker.component';
import {
  AppliedOrderSetSummary,
  OrderSetService,
  OrderSetSummary,
} from '../../services/order-set.service';

describe('OrderSetPickerComponent', () => {
  let fixture: ComponentFixture<OrderSetPickerComponent>;
  let svc: jasmine.SpyObj<OrderSetService>;

  // The picker debounces typed search by 250ms. Tests wait 280ms via real
  // timers (setTimeout) so the RxJS pipeline runs without fakeAsync —
  // this codebase's test-setup loads zone.js/testing but no spec uses
  // fakeAsync, and switching to it surfaces a ProxyZone init issue.
  const DEBOUNCE_WAIT_MS = 280;

  const sepsisSet: OrderSetSummary = {
    id: 'os-1',
    name: 'Sepsis bundle',
    admissionType: 'EMERGENCY',
    hospitalId: 'h1',
    orderItems: [
      { orderType: 'LAB', orderName: 'Lactate' },
      { orderType: 'MEDICATION', medicationName: 'Ceftriaxone' },
    ],
    active: true,
    version: 1,
    orderCount: 2,
  };

  const appliedResult: AppliedOrderSetSummary = {
    orderSetId: 'os-1',
    orderSetName: 'Sepsis bundle',
    orderSetVersion: 1,
    admissionId: 'adm-1',
    encounterId: 'enc-1',
    prescriptionIds: ['p-1'],
    labOrderIds: ['l-1'],
    imagingOrderIds: [],
    skippedItemCount: 0,
    cdsAdvisories: [],
  };

  beforeEach(async () => {
    svc = jasmine.createSpyObj<OrderSetService>('OrderSetService', ['list', 'apply']);
    svc.list.and.returnValue(of({ content: [sepsisSet], totalElements: 1, number: 0, size: 20 }));
    svc.apply.and.returnValue(of(appliedResult));

    await TestBed.configureTestingModule({
      imports: [OrderSetPickerComponent, TranslateModule.forRoot()],
      providers: [{ provide: OrderSetService, useValue: svc }],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderSetPickerComponent);
    fixture.componentRef.setInput('hospitalId', 'h1');
    fixture.componentRef.setInput('admissionId', 'adm-1');
    fixture.componentRef.setInput('encounterId', 'enc-1');
    fixture.componentRef.setInput('orderingStaffId', 's-1');
  });

  it('searches on init and renders results', (done) => {
    fixture.detectChanges();
    setTimeout(() => {
      fixture.detectChanges();
      expect(svc.list).toHaveBeenCalledWith('h1', '', 0, 200); // QA D1347
      const items = fixture.nativeElement.querySelectorAll('[data-testid="order-set-picker-item"]');
      expect(items.length).toBe(1);
      expect(items[0].textContent).toContain('Sepsis bundle');
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('debounces typed search and re-queries the service', (done) => {
    fixture.detectChanges();
    setTimeout(() => {
      const input = fixture.nativeElement.querySelector(
        '[data-testid="order-set-picker-search"]',
      ) as HTMLInputElement;
      input.value = 'sepsis';
      input.dispatchEvent(new Event('input'));
      setTimeout(() => {
        fixture.detectChanges();
        expect(svc.list).toHaveBeenCalledWith('h1', 'sepsis', 0, 200); // QA D1347
        done();
      }, DEBOUNCE_WAIT_MS);
    }, DEBOUNCE_WAIT_MS);
  });

  it('applies the selected set and emits the summary', (done) => {
    const emissions: AppliedOrderSetSummary[] = [];
    fixture.componentInstance.applied.subscribe((s) => emissions.push(s));

    fixture.detectChanges();
    setTimeout(() => {
      fixture.detectChanges();

      const item = fixture.nativeElement.querySelector(
        '[data-testid="order-set-picker-item"]',
      ) as HTMLElement;
      item.click();
      fixture.detectChanges();

      const applyBtn = fixture.nativeElement.querySelector(
        '[data-testid="order-set-picker-apply"]',
      ) as HTMLButtonElement;
      applyBtn.click();
      fixture.detectChanges();

      expect(svc.apply).toHaveBeenCalledOnceWith('os-1', 'adm-1', {
        encounterId: 'enc-1',
        orderingStaffId: 's-1',
      });
      expect(emissions).toEqual([appliedResult]);
      expect(
        fixture.nativeElement.querySelector('[data-testid="order-set-picker-applied"]'),
      ).not.toBeNull();
      // QA D1345 class: once applied, the same preview cannot apply the set again.
      expect(
        fixture.nativeElement.querySelector('[data-testid="order-set-picker-apply"]'),
      ).toBeNull();
      (fixture.componentInstance as unknown as { apply(os: unknown): void }).apply({ id: 'os-1' });
      expect(svc.apply).toHaveBeenCalledTimes(1);
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  /** Picks the set, then runs `then` once the search has landed. */
  function pick(set: typeof sepsisSet, then: () => void, done: DoneFn): void {
    svc.list.and.returnValue(of({ content: [set], totalElements: 1, number: 0, size: 200 }));
    fixture.detectChanges();
    setTimeout(() => {
      fixture.detectChanges();
      (
        fixture.nativeElement.querySelector('[data-testid="order-set-picker-item"]') as HTMLElement
      ).click();
      fixture.detectChanges();
      then();
      done();
    }, DEBOUNCE_WAIT_MS);
  }

  const text = (testId: string) =>
    (fixture.nativeElement.querySelector(`[data-testid="${testId}"]`)?.textContent ?? '') as string;

  /** QA D1396: the apply's refusal is the server's own reason, and the set stays open to retry. */
  it('QA D1396: an apply refused by the server shows its reason, never the generic line', (done) => {
    const reason = 'Élément 1 (Morphine) : un stupéfiant doit indiquer la quantité à délivrer.';
    svc.apply.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: reason, fieldErrors: { 'orderItems[0].quantity': reason } },
      })),
    );
    pick(
      sepsisSet,
      () => {
        (
          fixture.nativeElement.querySelector(
            '[data-testid="order-set-picker-apply"]',
          ) as HTMLButtonElement
        ).click();
        fixture.detectChanges();
        expect(text('order-set-picker-error')).toContain(reason);
        expect(text('order-set-picker-error')).not.toContain('ORDER_SETS.ERROR');
        expect(fixture.nativeElement.querySelector('[data-testid="order-set-picker-apply"]'))
          .withContext('another try stays possible')
          .not.toBeNull();
      },
      done,
    );
  });

  it('QA D1396: a failure with no reason of its own keeps the generic line', (done) => {
    svc.apply.and.returnValue(throwError(() => ({ status: 500, error: { message: 'boom' } })));
    pick(
      sepsisSet,
      () => {
        (
          fixture.nativeElement.querySelector(
            '[data-testid="order-set-picker-apply"]',
          ) as HTMLButtonElement
        ).click();
        fixture.detectChanges();
        expect(text('order-set-picker-error')).toContain('ORDER_SETS.ERROR');
        expect(text('order-set-picker-error')).not.toContain('boom');
      },
      done,
    );
  });

  /** QA D1405: two items of one drug are told apart, in the reader's language. */
  it('QA D1405: translated type and route, dose, frequency, interval and quantity on each item', (done) => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation(
      'fr',
      {
        ORDER_SETS: {
          ITEM_TYPE: { MEDICATION: 'Médicament' },
          ROUTE: { PO: 'Orale' },
          FREQUENCY: { Q6H: 'Toutes les 6 h', PRN: 'Si besoin (PRN)' },
          ITEM_ONE: 'élément',
          ITEMS: 'éléments',
        },
        PRESCRIPTIONS: {
          PRN_MIN_INTERVAL_VALUE: 'Au moins {{hours}} h entre deux doses',
          QUANTITY_VALUE: '{{value}} {{unit}}',
        },
      },
      true,
    );
    translate.use('fr');
    pick(
      {
        ...sepsisSet,
        orderCount: 1,
        orderItems: [
          {
            orderType: 'MEDICATION',
            medicationName: 'Paracétamol',
            doseRangeMin: 500,
            doseUnit: 'mg',
            route: 'PO',
            frequency: 'Q6H',
          },
          {
            orderType: 'MEDICATION',
            medicationName: 'Paracétamol',
            dose: '1 g',
            frequency: 'si fièvre',
            asNeeded: true,
            prnMinIntervalHours: 6,
            quantity: 6,
            quantityUnit: 'cp',
          },
          { orderType: 'LAB', orderName: 'Lactate' },
        ],
      } as typeof sepsisSet,
      () => {
        const items = Array.from(
          fixture.nativeElement.querySelectorAll('[data-testid="order-set-picker-preview-item"]'),
        ).map((li) => ((li as HTMLElement).textContent ?? '').replace(/\s+/g, ' ').trim());
        expect(items[0]).toContain('Médicament');
        expect(items[0]).not.toContain('MEDICATION');
        expect(items[0]).toContain('500 mg · Orale · Toutes les 6 h');
        expect(items[1]).toContain(
          '1 g · si fièvre · Si besoin (PRN) · Au moins 6 h entre deux doses · 6 cp',
        );
        expect(items[0]).not.toEqual(items[1]);
        expect(text('order-set-picker-list')).toContain('1 élément');
        expect(text('order-set-picker-list')).not.toContain('éléments');
      },
      done,
    );
  });

  it('QA D1405: one order created reads in the singular', (done) => {
    svc.apply.and.returnValue(of({ ...appliedResult, labOrderIds: [] }));
    pick(
      sepsisSet,
      () => {
        (
          fixture.nativeElement.querySelector(
            '[data-testid="order-set-picker-apply"]',
          ) as HTMLButtonElement
        ).click();
        fixture.detectChanges();
        expect(text('order-set-picker-applied')).toContain('ORDER_SETS.APPLIED_RESULT_ONE');
      },
      done,
    );
  });

  it('shows the error state when search fails', (done) => {
    svc.list.and.returnValue(throwError(() => new Error('500')));
    fixture.detectChanges();
    setTimeout(() => {
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('[data-testid="order-set-picker-error"]'),
      ).not.toBeNull();
      done();
    }, DEBOUNCE_WAIT_MS);
  });

  it('emits closed when the close button is clicked', () => {
    let closed = false;
    fixture.componentInstance.closed.subscribe(() => (closed = true));
    fixture.detectChanges();

    const btn = fixture.nativeElement.querySelector(
      '[data-testid="order-set-picker-close"]',
    ) as HTMLButtonElement;
    btn.click();

    expect(closed).toBeTrue();
  });
});
