import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { BillingComponent } from './billing';
import { BillingService, BillingInvoiceResponse, InvoicePage } from '../services/billing.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';

function mockInvoice(overrides: Partial<BillingInvoiceResponse> = {}): BillingInvoiceResponse {
  return {
    id: 'inv-1',
    invoiceNumber: 'INV-0001',
    patientFullName: 'John Doe',
    patientEmail: 'john@example.com',
    hospitalName: 'City Hospital',
    invoiceDate: '2026-08-01',
    dueDate: '2026-09-01',
    totalAmount: 1000,
    amountPaid: 0,
    balanceDue: 1000,
    status: 'SENT',
    ...overrides,
  } as BillingInvoiceResponse;
}

function mockPage(content: BillingInvoiceResponse[]): InvoicePage {
  return {
    content,
    number: 0,
    totalPages: 1,
    totalElements: content.length,
    size: 20,
  } as InvoicePage;
}

describe('BillingComponent', () => {
  let component: BillingComponent;
  let fixture: ComponentFixture<BillingComponent>;
  let billingSpy: jasmine.SpyObj<BillingService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    billingSpy = jasmine.createSpyObj('BillingService', [
      'searchInvoices',
      'getOverdue',
      'createInvoice',
      'updateInvoice',
      'deleteInvoice',
      'getInvoicePdf',
      'emailInvoice',
      'recordPayment',
    ]);
    billingSpy.searchInvoices.and.returnValue(of(mockPage([mockInvoice()])));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const permissionSpy = jasmine.createSpyObj('PermissionService', ['hasAnyPermission']);
    permissionSpy.hasAnyPermission.and.returnValue(true);
    const roleCtx = {
      hasAnyActiveRole: () => true,
    } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [BillingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BillingService, useValue: billingSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: PermissionService, useValue: permissionSpy },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BillingComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads invoices on init via server-side search', () => {
    fixture.detectChanges();
    expect(billingSpy.searchInvoices).toHaveBeenCalled();
    expect(component.invoices().length).toBe(1);
    expect(component.loading()).toBeFalse();
    expect(component.totalElements()).toBe(1);
  });

  it('shows an error toast when loading fails', () => {
    billingSpy.searchInvoices.and.returnValue(throwError(() => new Error('boom')));
    fixture.detectChanges();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
  });

  it('paid tab requests only PAID invoices', () => {
    fixture.detectChanges();
    billingSpy.searchInvoices.calls.reset();
    component.setTab('paid');
    const filters = billingSpy.searchInvoices.calls.mostRecent().args[0];
    expect(filters.statuses).toEqual(['PAID']);
  });

  it('outstanding tab requests SENT and PARTIALLY_PAID invoices', () => {
    fixture.detectChanges();
    billingSpy.searchInvoices.calls.reset();
    component.setTab('outstanding');
    const filters = billingSpy.searchInvoices.calls.mostRecent().args[0];
    expect(filters.statuses).toEqual(['SENT', 'PARTIALLY_PAID']);
  });

  it('overdue tab uses the dedicated server worklist', () => {
    billingSpy.getOverdue.and.returnValue(of([mockInvoice({ id: 'inv-2' })]));
    fixture.detectChanges();
    component.setTab('overdue');
    expect(billingSpy.getOverdue).toHaveBeenCalled();
    expect(component.invoices()[0].id).toBe('inv-2');
  });

  it('applies the client-side search filter', () => {
    fixture.detectChanges();
    component.searchTerm = 'nobody';
    component.applyFilter();
    expect(component.filtered().length).toBe(0);
    component.searchTerm = 'john';
    component.applyFilter();
    expect(component.filtered().length).toBe(1);
  });

  it('clearServerFilters resets filters and reloads', () => {
    fixture.detectChanges();
    component.filterStatus.set('PAID');
    component.filterFromDate.set('2026-01-01');
    billingSpy.searchInvoices.calls.reset();
    component.clearServerFilters();
    expect(component.filterStatus()).toBe('');
    expect(component.filterFromDate()).toBe('');
    expect(billingSpy.searchInvoices).toHaveBeenCalled();
  });

  it('canPayInvoice requires an open status and a positive balance', () => {
    expect(component.canPayInvoice(mockInvoice({ status: 'SENT', balanceDue: 100 }))).toBeTrue();
    expect(
      component.canPayInvoice(mockInvoice({ status: 'PARTIALLY_PAID', balanceDue: 1 })),
    ).toBeTrue();
    expect(component.canPayInvoice(mockInvoice({ status: 'PAID', balanceDue: 0 }))).toBeFalse();
    expect(component.canPayInvoice(mockInvoice({ status: 'DRAFT', balanceDue: 100 }))).toBeFalse();
    expect(component.canPayInvoice(mockInvoice({ status: 'SENT', balanceDue: 0 }))).toBeFalse();
  });

  it('recordPayment rejects an amount above the balance due', () => {
    const inv = mockInvoice({ balanceDue: 50 });
    component.paymentInv.set(inv);
    component.paymentAmount.set(100);
    component.recordPayment();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(billingSpy.recordPayment).not.toHaveBeenCalled();
  });

  it('recordPayment rejects a missing or non-positive amount', () => {
    component.paymentInv.set(mockInvoice());
    component.paymentAmount.set(0);
    component.recordPayment();
    expect(billingSpy.recordPayment).not.toHaveBeenCalled();
  });

  it('recordPayment patches the invoice list in place on success', () => {
    fixture.detectChanges();
    const updated = mockInvoice({ status: 'PAID', amountPaid: 1000, balanceDue: 0 });
    billingSpy.recordPayment.and.returnValue(of(updated));
    component.openPayment(component.invoices()[0]);
    expect(component.paymentAmount()).toBe(1000); // prefilled with balance
    component.recordPayment();
    expect(billingSpy.recordPayment).toHaveBeenCalledWith('inv-1', 1000);
    expect(component.invoices()[0].status).toBe('PAID');
    expect(component.showPaymentModal()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('sendEmail requires at least one recipient', () => {
    component.emailInv.set(mockInvoice());
    component.emailTo.set('  ');
    component.sendEmail();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(billingSpy.emailInvoice).not.toHaveBeenCalled();
  });

  it('sendEmail splits recipients and reloads after success', () => {
    fixture.detectChanges();
    billingSpy.emailInvoice.and.returnValue(of({}) as never);
    billingSpy.searchInvoices.calls.reset();
    component.openEmail(component.invoices()[0]);
    expect(component.emailTo()).toBe('john@example.com');
    component.emailTo.set('a@x.test, b@x.test');
    component.sendEmail();
    const [, payload] = billingSpy.emailInvoice.calls.mostRecent().args;
    expect(payload.to).toEqual(['a@x.test', 'b@x.test']);
    expect(payload.attachPdf).toBeTrue();
    // Email flips the invoice to SENT server-side → list is re-fetched.
    expect(billingSpy.searchInvoices).toHaveBeenCalled();
  });

  it('submitForm creates a new invoice and closes the modal', () => {
    fixture.detectChanges();
    billingSpy.createInvoice.and.returnValue(of(mockInvoice()));
    component.openCreate();
    component.submitForm();
    expect(billingSpy.createInvoice).toHaveBeenCalled();
    expect(component.showModal()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('submitForm updates when editing', () => {
    fixture.detectChanges();
    billingSpy.updateInvoice.and.returnValue(of(mockInvoice()));
    component.openEdit(component.invoices()[0]);
    expect(component.editing()).toBeTrue();
    component.submitForm();
    expect(billingSpy.updateInvoice).toHaveBeenCalledWith('inv-1', jasmine.anything());
  });

  it('executeDelete removes the invoice and reloads', () => {
    fixture.detectChanges();
    billingSpy.deleteInvoice.and.returnValue(of('deleted'));
    component.confirmDelete(component.invoices()[0]);
    component.executeDelete();
    expect(billingSpy.deleteInvoice).toHaveBeenCalledWith('inv-1');
    expect(component.showDeleteConfirm()).toBeFalse();
  });

  it('computes stats from the loaded page', () => {
    billingSpy.searchInvoices.and.returnValue(
      of(
        mockPage([
          mockInvoice({ id: 'a', status: 'PAID', amountPaid: 500, balanceDue: 0 }),
          mockInvoice({ id: 'b', status: 'SENT', amountPaid: 0, balanceDue: 300 }),
        ]),
      ),
    );
    fixture.detectChanges();
    const stats = component.stats();
    expect(stats.paid).toBe(1);
    expect(stats.outstanding).toBe(1);
    expect(stats.totalRevenue).toBe(500);
    expect(stats.balance).toBe(300);
  });

  it('maps statuses to badge classes and display labels', () => {
    expect(component.getStatusClass('PAID')).toContain('status-paid');
    expect(component.getStatusClass('PARTIALLY_PAID')).toContain('status-partial');
    expect(component.getStatusClass('CANCELLED')).toContain('status-cancelled');
    expect(component.formatStatus('PARTIALLY_PAID')).toBe('PARTIALLY PAID');
    expect(component.formatStatus('')).toBe('—');
  });

  it('shows a toast when the PDF download fails', () => {
    billingSpy.getInvoicePdf.and.returnValue(throwError(() => new Error('nope')));
    component.downloadPdf('inv-1');
    expect(toastSpy.error).toHaveBeenCalled();
  });
});
