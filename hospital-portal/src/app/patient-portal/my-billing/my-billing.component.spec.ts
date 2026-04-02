import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { MyBillingComponent } from './my-billing.component';
import { PatientPortalService } from '../../services/patient-portal.service';

describe('MyBillingComponent', () => {
  let component: MyBillingComponent;
  let fixture: ComponentFixture<MyBillingComponent>;

  const mockPortalService = {
    getMyInvoices: () => of([]),
    payInvoice: () => of({}),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyBillingComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: mockPortalService }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyBillingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty state when no invoices', () => {
    expect(component.invoices().length).toBe(0);
    expect(component.loading()).toBe(false);
  });

  it('should open and close pay dialog', () => {
    const mockInvoice = { id: 'inv1', invoiceNumber: '001', balance: 100 } as any;
    component.openPayDialog(mockInvoice);
    expect(component.payingInvoice()).toBeTruthy();
    expect(component.payAmount).toBe(100);

    component.closePayDialog();
    expect(component.payingInvoice()).toBeNull();
  });

  it('should not submit when amount exceeds balance', () => {
    const mockInvoice = { id: 'inv1', invoiceNumber: '001', balance: 50 } as any;
    component.openPayDialog(mockInvoice);
    component.payAmount = 100;
    component.submitPayment();
    expect(component.payError()).toContain('exceed');
  });
});
