import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PaymentPendingPanelComponent } from './payment-pending-panel.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ReceptionService } from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { of } from 'rxjs';

describe('PaymentPendingPanelComponent', () => {
  let component: PaymentPendingPanelComponent;
  let fixture: ComponentFixture<PaymentPendingPanelComponent>;

  const mockReceptionService = {
    recordPayment: () => of({}),
  };

  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PaymentPendingPanelComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
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
});
