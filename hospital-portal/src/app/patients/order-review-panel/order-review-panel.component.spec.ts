import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { OrderReviewRow, OrderReviewService } from '../../services/order-review.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { expectEmptySubmitRefusal, expectFieldsMarked } from '../../testing/form-refusal-behaviour';
import { OrderReviewPanelComponent } from './order-review-panel.component';

function row(overrides: Partial<OrderReviewRow> = {}): OrderReviewRow {
  return {
    id: 'h1',
    disputeId: 'x1',
    orderType: 'PRESCRIPTION',
    orderId: 'o1',
    orderLabel: 'Morphine',
    orderVersion: 3,
    currentOrderVersion: 4,
    status: 'HELD',
    controlled: false,
    decidedByName: null,
    decidedAt: null,
    reason: null,
    replacementOrderId: null,
    replacementCandidates: [{ id: 'n1', label: 'Morphine 5 mg' }],
    ...overrides,
  };
}

describe('OrderReviewPanelComponent', () => {
  let fixture: ComponentFixture<OrderReviewPanelComponent>;
  let spy: jasmine.SpyObj<OrderReviewService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  let prescriber = true;

  const host = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    prescriber = true;
    spy = jasmine.createSpyObj('OrderReviewService', ['forPatient', 'decide']);
    spy.forPatient.and.returnValue(of([row()]));
    spy.decide.and.returnValue(of(row({ status: 'CONTINUED' })));
    confirm = jasmine.createSpyObj('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);
    await TestBed.configureTestingModule({
      imports: [OrderReviewPanelComponent, TranslateModule.forRoot()],
      providers: [
        { provide: OrderReviewService, useValue: spy },
        { provide: ConfirmService, useValue: confirm },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: RoleContextService, useValue: { hasAnyActiveRole: () => prescriber } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(OrderReviewPanelComponent);
    fixture.componentRef.setInput('patientId', 'p1');
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
  });

  afterEach(() => fixture.nativeElement.remove());

  it('shows the held order with continue, replace and stop', () => {
    expect(host().querySelector('[data-testid="order-review-panel"]')).not.toBeNull();
    for (const id of ['continue', 'replace', 'stop']) {
      expect(host().querySelector(`[data-testid="order-review-${id}"]`)).not.toBeNull();
    }
  });

  it('renders nothing when the chart has no row', () => {
    spy.forPatient.and.returnValue(of([]));
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="order-review-panel"]')).toBeNull();
  });

  it('offers a non-prescriber no decision', () => {
    prescriber = false;
    fixture.componentInstance.load();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="order-review-continue"]')).toBeNull();
  });

  it('an empty reason is marked and nothing is sent', () => {
    fixture.componentInstance.open(row(), 'continue');
    fixture.detectChanges();
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="order-review-submit"]',
      sent: spy.decide,
    });
    expect(marked).toContain('ore-reason');
  });

  it('continuing names the version the reader sees, and takes a reason of its own', async () => {
    const c = fixture.componentInstance;
    c.open(row(), 'continue');
    c.reason = ' Still indicated ';
    await c.submit();
    expect(confirm.ask).toHaveBeenCalled();
    expect(spy.decide).toHaveBeenCalledWith(
      'h1',
      'continue',
      jasmine.objectContaining({
        reason: 'Still indicated',
        orderVersion: 4,
      }),
    );
  });

  it('replacing needs the new order', async () => {
    const c = fixture.componentInstance;
    c.open(row(), 'replace');
    c.reason = 'Dose changed';
    await c.submit();
    expect(spy.decide).not.toHaveBeenCalled();

    c.replacementOrderId = 'n1';
    await c.submit();
    expect(spy.decide).toHaveBeenCalledWith(
      'h1',
      'replace',
      jasmine.objectContaining({ replacementOrderId: 'n1' }),
    );
  });

  it('asks a controlled drug for the step-up', () => {
    fixture.componentInstance.open(row({ controlled: true }), 'continue');
    fixture.detectChanges();
    expect(host().querySelector('#ore-password')).not.toBeNull();
  });

  it('marks what the server refuses, and reads the order again', async () => {
    spy.decide.and.returnValue(
      throwError(() => ({
        error: { message: 'Changed', fieldErrors: { reason: 'Reason refused' } },
      })),
    );
    const c = fixture.componentInstance;
    c.open(row(), 'continue');
    fixture.detectChanges();
    c.reason = 'x';
    spy.forPatient.calls.reset();
    await c.submit();
    fixture.detectChanges();
    expectFieldsMarked(fixture, { 'ore-reason': 'Reason refused' });
    expect(spy.forPatient).toHaveBeenCalled();
  });

  it('sends nothing when the confirmation is declined', async () => {
    confirm.ask.and.resolveTo(false);
    const c = fixture.componentInstance;
    c.open(row(), 'stop');
    c.reason = 'x';
    await c.submit();
    expect(spy.decide).not.toHaveBeenCalled();
  });
});
