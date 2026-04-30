import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
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
      expect(svc.list).toHaveBeenCalledWith('h1', '');
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
        expect(svc.list).toHaveBeenCalledWith('h1', 'sepsis');
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
      done();
    }, DEBOUNCE_WAIT_MS);
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
