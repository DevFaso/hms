import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PaymentPendingPanelComponent } from './payment-pending-panel.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { ReceptionService } from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { of } from 'rxjs';

describe('PaymentPendingPanelComponent', () => {
  let component: PaymentPendingPanelComponent;
  let fixture: ComponentFixture<PaymentPendingPanelComponent>;

  const mockReceptionService = {
    recordPayment: (_invoiceId: string, _amount: number, _method?: string, _reference?: string) =>
      of({}),
  };

  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PaymentPendingPanelComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PaymentPendingPanelComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no items', () => {
    component.items = [];
    fixture.detectChanges();
    const el = fixture.nativeElement.querySelector('.empty-state');
    expect(el).toBeTruthy();
  });

  it('should open pay dialog with item', () => {
    const item = { patientId: 'p1', patientName: 'Test', appointmentId: 'a1' } as any;
    component.openPayDialog(item);
    expect(component.payingItem()).toEqual(item);
    expect(component.payMethod()).toBe('CASH');
  });

  it('should close pay dialog', () => {
    component.payingItem.set({ patientId: 'p1' } as any);
    component.closePayDialog();
    expect(component.payingItem()).toBeNull();
  });

  it('should not submit when no invoice selected', () => {
    component.selectedInvoiceId.set(null);
    component.submitPayment();
    expect(component.paying()).toBe(false);
  });

  it('QA D948: offers Mobile Money, needs its reference, and sends it', () => {
    expect(component.methods.map((m) => m.value)).toContain('MOBILE_MONEY');
    const spy = spyOn(mockReceptionService, 'recordPayment').and.returnValue(of({}));
    component.payingItem.set({ patientId: 'p1', patientName: 'Seydou' } as any);
    component.selectedInvoiceId.set('inv-1');
    component.payAmount.set(1500);
    component.payMethod.set('MOBILE_MONEY');

    component.submitPayment();
    expect(spy).not.toHaveBeenCalled();

    component.payReference.set(' OM-778812 ');
    component.submitPayment();
    expect(spy).toHaveBeenCalledWith('inv-1', 1500, 'MOBILE_MONEY', 'OM-778812');
  });

  it('QA D948: shows francs CFA without decimals', () => {
    component.payingItem.set({ patientId: 'p1', patientName: 'Seydou' } as any);
    component.openInvoices.set([
      {
        id: 'inv-1',
        invoiceNumber: 'F-1',
        totalAmount: 1500,
        amountPaid: 0,
        balanceDue: 1500,
        status: 'SENT',
      },
    ] as any);
    component.loadingInvoices.set(false);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('F\u00a0CFA');
    expect(text).not.toContain('1 500,00');
  });
});
