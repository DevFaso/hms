import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

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
      'getInvoice',
      'getInvoiceItems',
      'createInvoiceItem',
      'deleteInvoiceItem',
      'listPayments',
      'confirmPayment',
      'rejectPayment',
      'reversePayment',
      'getReceiptPdf',
      'getSummary',
    ]);
    billingSpy.listPayments.and.returnValue(of([]));
    billingSpy.getSummary.and.returnValue(
      of({ total: 0, paid: 0, outstanding: 0, overdue: 0, totalRevenue: 0, balance: 0 }),
    );
    billingSpy.searchInvoices.and.returnValue(of(mockPage([mockInvoice()])));
    billingSpy.getInvoiceItems.and.returnValue(of([]));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const permissionSpy = jasmine.createSpyObj('PermissionService', ['hasAnyPermission']);
    permissionSpy.hasAnyPermission.and.returnValue(true);
    const roleCtx = {
      hasAnyActiveRole: () => true,
    } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [BillingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
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

  it('QA D203: the search term goes to the server, not a filter over the loaded page', fakeAsync(() => {
    fixture.detectChanges();
    billingSpy.searchInvoices.calls.reset();
    component.searchTerm = ' GH-2026-000024 ';
    component.onSearchInput();
    tick(300);
    expect(billingSpy.searchInvoices).toHaveBeenCalledTimes(1);
    const [filters, page] = billingSpy.searchInvoices.calls.mostRecent().args;
    expect(filters.query).toBe('GH-2026-000024');
    expect(page).toBe(0);
    // The server's rows are shown as they are: no second, page-local filter.
    expect(component.filtered().length).toBe(1);
  }));

  it('filters the whole overdue worklist locally', () => {
    billingSpy.getOverdue.and.returnValue(of([mockInvoice({ id: 'o1' })]));
    fixture.detectChanges();
    component.setTab('overdue');
    component.searchTerm = 'nobody';
    component.onSearchInput();
    expect(component.filtered().length).toBe(0);
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
    expect(billingSpy.recordPayment).toHaveBeenCalledWith('inv-1', {
      amount: 1000,
      method: 'CASH',
      reference: undefined,
    });
    expect(component.invoices()[0].status).toBe('PAID');
    expect(component.showPaymentModal()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('QA D146: recordPayment sends the chosen method and reference', () => {
    fixture.detectChanges();
    billingSpy.recordPayment.and.returnValue(of(mockInvoice({ status: 'PARTIALLY_PAID' })));
    component.openPayment(component.invoices()[0]);
    component.paymentAmount.set(500);
    component.paymentMethod.set('MOBILE_MONEY');
    component.paymentReference.set(' MM-778 ');
    component.recordPayment();
    expect(billingSpy.recordPayment).toHaveBeenCalledWith('inv-1', {
      amount: 500,
      method: 'MOBILE_MONEY',
      reference: 'MM-778',
    });
  });

  it('QA D184: recordPayment refuses a fractional franc amount', () => {
    component.paymentInv.set(mockInvoice());
    component.paymentAmount.set(0.5);
    component.recordPayment();
    expect(billingSpy.recordPayment).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalled();
  });

  it('QA D147: a paid invoice takes no line changes', () => {
    expect(component.canEditLines(mockInvoice({ status: 'PAID' }))).toBeFalse();
    expect(component.canEditLines(mockInvoice({ status: 'PARTIALLY_PAID' }))).toBeTrue();
  });

  it('QA D142: a reversal needs a reason and refreshes the invoice', () => {
    fixture.detectChanges();
    const inv = component.invoices()[0];
    component.viewInvoice(inv);
    const row = {
      id: 'p1',
      amount: 1000,
      paymentDate: '2026-10-18',
      paymentMethod: 'CASH',
      status: 'CONFIRMED' as const,
    };
    component.openReversal(row, 'reverse');
    component.submitReversal();
    expect(billingSpy.reversePayment).not.toHaveBeenCalled();

    billingSpy.reversePayment.and.returnValue(of(mockInvoice({ status: 'SENT', amountPaid: 0 })));
    component.reversalReason.set('Refunded');
    component.submitReversal();
    expect(billingSpy.reversePayment).toHaveBeenCalledWith('inv-1', 'p1', 'Refunded');
    expect(component.reversingPayment()).toBeNull();
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

  it('a create carries the picked patient and one idempotency key, and a double submit sends once (D92, D94)', () => {
    fixture.detectChanges();
    const pending = new Subject<BillingInvoiceResponse>();
    billingSpy.createInvoice.and.returnValue(pending.asObservable());
    component.openCreate();
    component.onPatientPicked({
      id: 'pat-9',
      firstName: 'Awa',
      lastName: 'Sawadogo',
      active: true,
    });
    component.submitForm();
    component.submitForm();
    expect(billingSpy.createInvoice).toHaveBeenCalledTimes(1);
    const req = billingSpy.createInvoice.calls.mostRecent().args[0];
    expect(req.patientId).toBe('pat-9');
    expect(req.clientRequestId).toBeTruthy();
    expect('amountPaid' in req).toBeFalse();
    expect('invoiceNumber' in req).toBeFalse();
  });

  it('offers only DRAFT/SENT on create and locks a paid status on edit (D91)', () => {
    fixture.detectChanges();
    component.openCreate();
    expect(component.statusOptions()).toEqual(['DRAFT', 'SENT']);
    component.openEdit(mockInvoice({ status: 'PARTIALLY_PAID' }));
    expect(component.statusOptions()).toEqual(['PARTIALLY_PAID']);
    expect(component.statusLocked()).toBeTrue();
  });

  it('adding a line re-reads the invoice so the recomputed total shows (D72)', () => {
    fixture.detectChanges();
    const inv = component.invoices()[0];
    billingSpy.createInvoiceItem.and.returnValue(
      of({
        id: 'line-1',
        billingInvoiceId: inv.id,
        itemDescription: 'Consultation',
        itemCategory: 'SERVICE',
        quantity: 2,
        unitPrice: 2500,
        totalPrice: 5000,
      }),
    );
    billingSpy.getInvoice.and.returnValue(of(mockInvoice({ totalAmount: 5000, balanceDue: 5000 })));
    component.viewInvoice(inv);
    component.newItemDescription.set('Consultation');
    component.newItemQuantity.set(2);
    component.newItemUnitPrice.set(2500);
    component.addItem();
    const req = billingSpy.createInvoiceItem.calls.mostRecent().args[0];
    expect(req.billingInvoiceId).toBe(inv.id);
    expect(req.unitPrice).toBe(2500);
    expect(component.selectedInvoice()?.totalAmount).toBe(5000);
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

  it('QA D209: the KPIs come from the server summary, not the loaded page', () => {
    billingSpy.getSummary.and.returnValue(
      of({ total: 32, paid: 20, outstanding: 9, overdue: 2, totalRevenue: 157200, balance: 12900 }),
    );
    billingSpy.searchInvoices.and.returnValue(
      of(mockPage([mockInvoice({ id: 'a', status: 'PAID', amountPaid: 500, balanceDue: 0 })])),
    );
    fixture.detectChanges();
    const stats = component.stats();
    expect(billingSpy.getSummary).toHaveBeenCalled();
    expect(stats.total).toBe(32);
    expect(stats.totalRevenue).toBe(157200);
    expect(stats.balance).toBe(12900);
  });

  it('QA D325: the All tab counts every invoice in scope, not the loaded page', () => {
    billingSpy.getSummary.and.returnValue(
      of({ total: 41, paid: 20, outstanding: 9, overdue: 2, totalRevenue: 0, balance: 0 }),
    );
    billingSpy.searchInvoices.and.returnValue(of(mockPage([mockInvoice({ id: 'a' })])));
    fixture.detectChanges();
    const tab = (fixture.nativeElement as HTMLElement).querySelector('.tabs .tab') as HTMLElement;
    expect(tab.textContent).toContain('(41)');
  });

  it('maps statuses to badge classes', () => {
    expect(component.getStatusClass('PAID')).toContain('status-paid');
    expect(component.getStatusClass('PARTIALLY_PAID')).toContain('status-partial');
    expect(component.getStatusClass('CANCELLED')).toContain('status-cancelled');
    // formatStatus is gone: it rendered 'PARTIALLY PAID' in every language.
    // The template pipes InvoiceStatus through enumLabel now, so the assertion
    // that matters is the one in the DOM test below.
  });

  it('shows a toast when the PDF download fails', () => {
    billingSpy.getInvoicePdf.and.returnValue(throwError(() => new Error('nope')));
    component.downloadPdf('inv-1');
    expect(toastSpy.error).toHaveBeenCalled();
  });

  describe('payments ledger (QA D140/D142)', () => {
    const pending = {
      id: 'p9',
      amount: 400,
      paymentDate: '2026-10-18',
      paymentMethod: 'MOBILE_MONEY',
      status: 'PENDING' as const,
    };

    it('loads the ledger with the detail panel and clears it on close', () => {
      billingSpy.listPayments.and.returnValue(of([pending]));
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      expect(billingSpy.listPayments).toHaveBeenCalledWith('inv-1');
      expect(component.payments().length).toBe(1);
      component.closeDetail();
      expect(component.payments()).toEqual([]);
    });

    it('empties the ledger when it fails to load', () => {
      billingSpy.listPayments.and.returnValue(throwError(() => new Error('down')));
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      expect(component.payments()).toEqual([]);
    });

    it('confirms a pending self-payment and refreshes the invoice', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      billingSpy.confirmPayment.and.returnValue(of(mockInvoice({ status: 'PAID', balanceDue: 0 })));
      component.confirmPendingPayment(pending);
      expect(billingSpy.confirmPayment).toHaveBeenCalledWith('inv-1', 'p9');
      expect(component.selectedInvoice()?.status).toBe('PAID');
      expect(component.paymentActionBusy()).toBeFalse();
      expect(toastSpy.success).toHaveBeenCalled();
    });

    it('reports a refused confirmation', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      billingSpy.confirmPayment.and.returnValue(
        throwError(() => ({ error: { message: 'already confirmed' } })),
      );
      component.confirmPendingPayment(pending);
      expect(toastSpy.error).toHaveBeenCalledWith('already confirmed');
      expect(component.paymentActionBusy()).toBeFalse();
    });

    it('does nothing without a selected invoice', () => {
      component.confirmPendingPayment(pending);
      expect(billingSpy.confirmPayment).not.toHaveBeenCalled();
    });

    it('rejects a pending self-payment with a reason', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      billingSpy.rejectPayment.and.returnValue(of(mockInvoice()));
      component.openReversal(pending, 'reject');
      component.reversalReason.set(' wrong reference ');
      component.submitReversal();
      expect(billingSpy.rejectPayment).toHaveBeenCalledWith('inv-1', 'p9', 'wrong reference');
      expect(billingSpy.reversePayment).not.toHaveBeenCalled();
      expect(component.reversingPayment()).toBeNull();
    });

    it('keeps the reversal dialog open when the server refuses', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      billingSpy.reversePayment.and.returnValue(throwError(() => ({ error: {} })));
      component.openReversal(pending, 'reverse');
      component.reversalReason.set('Refunded');
      component.submitReversal();
      expect(toastSpy.error).toHaveBeenCalled();
      expect(component.reversingPayment()).not.toBeNull();
      component.closeReversal();
      expect(component.reversingPayment()).toBeNull();
    });

    it('downloads a receipt', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
      spyOn(URL, 'revokeObjectURL');
      const click = spyOn(HTMLAnchorElement.prototype, 'click');
      billingSpy.getReceiptPdf.and.returnValue(of(new Blob(['%PDF'])));
      component.downloadReceipt(pending);
      expect(billingSpy.getReceiptPdf).toHaveBeenCalledWith('inv-1', 'p9');
      expect(click).toHaveBeenCalled();
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:x');
    });

    it('reports a failed receipt download', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      billingSpy.getReceiptPdf.and.returnValue(throwError(() => new Error('nope')));
      component.downloadReceipt(pending);
      expect(toastSpy.error).toHaveBeenCalled();
    });

    it('does not remove a line the user did not confirm (QA D147)', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      spyOn(globalThis, 'confirm').and.returnValue(false);
      component.removeItem({ id: 'item-1' } as never);
      expect(billingSpy.deleteInvoiceItem).not.toHaveBeenCalled();
    });
  });

  it('renders the invoice status in French, not the wire token', () => {
    // formatStatus() used to produce this badge by swapping underscores for
    // spaces, so a French biller read "PARTIALLY PAID" and no i18n gate could
    // see it: the key existed and was translated, it was simply never asked
    // for. The label is now piped through the invoiceStatus domain.
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: { ENUM: { INVOICE_STATUS: { PARTIALLY_PAID: 'Partiellement payée' } } },
    });
    billingSpy.searchInvoices.and.returnValue(
      of(mockPage([mockInvoice({ status: 'PARTIALLY_PAID' })])),
    );

    fixture.detectChanges();

    const badges = Array.from(
      fixture.nativeElement.querySelectorAll('.status-badge') as NodeListOf<HTMLElement>,
    ).map((el) => (el.textContent ?? '').trim());
    expect(badges).toContain('Partiellement payée');
    expect(badges).not.toContain('PARTIALLY PAID');
    expect(badges).not.toContain('PARTIALLY_PAID');
  });

  it('falls back to — when an invoice has no status', () => {
    // formatStatus() returned '—' for a null status and EnumLabelPipe returns
    // '', so converting the badge to the pipe dropped the placeholder and left
    // an empty chip. This is the assertion that used to cover it, moved to the
    // DOM because that is where the fallback now lives.
    billingSpy.searchInvoices.and.returnValue(of(mockPage([mockInvoice({ status: undefined })])));

    fixture.detectChanges();

    const badges = Array.from(
      fixture.nativeElement.querySelectorAll('.status-badge') as NodeListOf<HTMLElement>,
    ).map((el) => (el.textContent ?? '').trim());
    expect(badges).toContain('—');
    expect(badges).not.toContain('');
  });
});

describe('BillingComponent as a billing specialist (QA D181)', () => {
  it('does not offer delete, which the server refuses to BILLING', async () => {
    const billingSpy = jasmine.createSpyObj('BillingService', ['searchInvoices', 'getOverdue']);
    billingSpy.searchInvoices.and.returnValue(of(mockPage([])));
    const permissionSpy = jasmine.createSpyObj('PermissionService', ['hasAnyPermission']);
    permissionSpy.hasAnyPermission.and.returnValue(true); // holds "Manage Billing"
    const roleCtx = {
      hasAnyActiveRole: (roles: string[]) => roles.includes('ROLE_BILLING_SPECIALIST'),
    } as unknown as RoleContextService;
    await TestBed.configureTestingModule({
      imports: [BillingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: BillingService, useValue: billingSpy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
        { provide: PermissionService, useValue: permissionSpy },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    }).compileComponents();
    const component = TestBed.createComponent(BillingComponent).componentInstance;
    expect(component.canDelete).toBeFalse();
    expect(component.canRecordPayment).toBeTrue();
  });
});
