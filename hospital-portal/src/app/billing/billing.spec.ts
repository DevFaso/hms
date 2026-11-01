import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpHeaders, HttpResponse, provideHttpClient, withXhr } from '@angular/common/http';
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
      'updateInvoiceItem',
      'deleteInvoiceItem',
      'listPayments',
      'confirmPayment',
      'rejectPayment',
      'reversePayment',
      'getReceiptPdf',
      'getSummary',
      'addAdjustment',
      'listAdjustments',
      'reverseAdjustment',
    ]);
    billingSpy.listAdjustments.and.returnValue(of([]));
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

  // QA D1145 (round 21): after a language switch a refused search showed
  // "No invoices found" with zero totals, which reads as an empty ledger.
  it('a failed read says so with a retry, never "No invoices found" and zero totals', () => {
    billingSpy.searchInvoices.and.returnValue(throwError(() => new Error('boom')));
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const failed = host.querySelector('[data-testid="billing-load-failed"]');
    expect(failed).withContext('the failure is on the page').not.toBeNull();
    expect(failed!.getAttribute('role')).toBe('alert');
    expect(host.querySelector('.empty-state:not(.load-failed)')).toBeNull();
    const values = Array.from(host.querySelectorAll('.summary-cards .card-value')).map((v) =>
      v.textContent!.trim(),
    );
    expect(values.length).toBeGreaterThan(0);
    expect(values.every((v) => v === '—'))
      .withContext(values.join(' | '))
      .toBeTrue();

    billingSpy.searchInvoices.and.returnValue(of(mockPage([mockInvoice()])));
    (failed!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="billing-load-failed"]')).toBeNull();
    expect(component.invoices().length).toBe(1);
    expect(host.querySelector('.summary-cards .card-value')!.textContent!.trim()).not.toBe('—');
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

  it('QA D634: the payment modal leaves a pending patient declaration out of the prefill and says so', () => {
    billingSpy.listPayments.and.returnValue(
      of([
        {
          id: 'p1',
          amount: 1000,
          paymentDate: '2026-10-23',
          paymentMethod: 'CARD',
          status: 'PENDING' as const,
        },
        {
          id: 'p2',
          amount: 500,
          paymentDate: '2026-10-22',
          paymentMethod: 'CASH',
          status: 'REJECTED' as const,
        },
      ]),
    );
    component.openPayment(mockInvoice({ id: 'inv-6', status: 'SENT', balanceDue: 3000 }));
    expect(billingSpy.listPayments).toHaveBeenCalledWith('inv-6');
    expect(component.paymentPending()).toBe(1000);
    expect(component.paymentAmount()).toBe(2000);
  });

  it('QA D634: with nothing pending the prefill is still the whole balance', () => {
    billingSpy.listPayments.and.returnValue(of([]));
    component.openPayment(mockInvoice({ id: 'inv-7', status: 'SENT', balanceDue: 3000 }));
    expect(component.paymentPending()).toBe(0);
    expect(component.paymentAmount()).toBe(3000);
  });

  it('QA D614 / D613: an issued invoice keeps its patient; only a draft is locked open', () => {
    component.openEdit(mockInvoice({ status: 'SENT' }));
    expect(component.patientLocked()).toBeTrue();
    component.openEdit(mockInvoice({ status: 'DRAFT' }));
    expect(component.patientLocked()).toBeFalse();
    component.openCreate();
    expect(component.patientLocked()).toBeFalse();
  });

  it('QA D656: an issued invoice offers no line form and locks its dates, total and visit', async () => {
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    component.viewInvoice(mockInvoice({ status: 'SENT' }));
    fixture.detectChanges();
    expect(el.querySelector('.line-form')).toBeNull();
    component.closeDetail();
    component.viewInvoice(mockInvoice({ status: 'DRAFT' }));
    fixture.detectChanges();
    expect(el.querySelector('.line-form')).not.toBeNull();
    component.closeDetail();

    component.openEdit(mockInvoice({ status: 'SENT' }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const date = el.querySelector<HTMLInputElement>('#bill-invoiceDate');
    expect(date?.disabled).toBeTrue();
    expect(el.querySelector<HTMLInputElement>('#bill-dueDate')?.disabled).toBeTrue();
    expect(el.querySelector<HTMLInputElement>('#bill-encounter')?.disabled).toBeTrue();
    // QA D1057: the notes are locked too (the server refuses a change, D656);
    // they used to stay editable and the cashier's cancellation reason was refused.
    expect(el.querySelector<HTMLTextAreaElement>('#bill-notes')?.disabled).toBeTrue();
    // QA D1057: an issued invoice never offers DRAFT, only itself or a cancellation.
    expect(component.statusOptions()).toEqual(['SENT', 'CANCELLED']);
  });

  it('QA D621: amounts read "F CFA" whatever the language', () => {
    expect(component.formatCurrency(4000)).toContain('F\u00a0CFA');
    expect(component.formatCurrency(4000)).not.toContain('XOF');
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
    // QA D656: an issued invoice, part-paid or not, takes no line changes.
    expect(component.canEditLines(mockInvoice({ status: 'PARTIALLY_PAID' }))).toBeFalse();
    expect(component.canEditLines(mockInvoice({ status: 'SENT' }))).toBeFalse();
    expect(component.canEditLines(mockInvoice({ status: 'DRAFT' }))).toBeTrue();
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

  it('QA D436: a new invoice is dated today and due 30 days later', () => {
    const form = component.emptyForm();
    const today = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    expect(form.invoiceDate).toBe(
      `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`,
    );
    const due = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 30);
    expect(form.dueDate).toBe(
      `${due.getFullYear()}-${pad(due.getMonth() + 1)}-${pad(due.getDate())}`,
    );
  });

  it('sendEmail requires at least one recipient', () => {
    component.emailInv.set(mockInvoice());
    component.emailTo.set('  ');
    component.sendEmail();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(billingSpy.emailInvoice).not.toHaveBeenCalled();
  });

  // QA D1179 (round 21): "pas-un-email" reached the server, whose raw Jakarta
  // text came back in a toast. The form refuses it on the field first.
  it('a malformed To or Cc address is refused on its field and nothing is sent', () => {
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
    try {
      component.openEmail(component.invoices()[0]);
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      component.emailTo.set('pas-un-email');
      component.emailCc.set('ok@x.test');
      component.sendEmail();
      expect(billingSpy.emailInvoice).not.toHaveBeenCalled();
      const to = host.querySelector<HTMLInputElement>('#email-to')!;
      expect(to.getAttribute('aria-invalid')).toBe('true');
      expect(host.querySelector('#email-cc')!.hasAttribute('aria-invalid')).toBeFalse();
      expect(document.activeElement).toBe(to);
      expect(toastSpy.error).toHaveBeenCalledWith('BILLING.EMAIL_ADDRESS_INVALID');

      component.emailTo.set('a@x.test');
      component.emailCc.set('b@x');
      component.sendEmail();
      expect(billingSpy.emailInvoice).not.toHaveBeenCalled();
      expect(to.hasAttribute('aria-invalid')).toBeFalse();
      expect(host.querySelector('#email-cc')!.getAttribute('aria-invalid')).toBe('true');
    } finally {
      (fixture.nativeElement as HTMLElement).remove();
    }
  });

  it('sendEmail splits recipients after success', () => {
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
    // QA D758: emailing no longer changes the status, so nothing is re-fetched.
    expect(billingSpy.searchInvoices).not.toHaveBeenCalled();
  });

  it('QA D758: a refused email shows the server reason', () => {
    billingSpy.emailInvoice.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { message: 'Brouillon: émettez-la d abord' },
      })) as never,
    );
    component.emailInv.set(mockInvoice());
    component.emailTo.set('a@x.test');
    component.sendEmail();
    expect(toastSpy.error).toHaveBeenCalledWith('Brouillon: émettez-la d abord');
  });

  it('QA D758: no email action on a DRAFT or CANCELLED invoice row', () => {
    billingSpy.searchInvoices.and.returnValue(
      of(
        mockPage([
          mockInvoice({ id: 'd', status: 'DRAFT' }),
          mockInvoice({ id: 'c', status: 'CANCELLED' }),
          mockInvoice({ id: 's', status: 'SENT' }),
        ]),
      ),
    );
    fixture.detectChanges();
    const mailButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.material-symbols-outlined'),
    ).filter((el) => el.textContent?.trim() === 'email');
    expect(mailButtons.length).toBe(1);
  });

  it('QA D529: a stay already invoiced asks before a supplementary invoice', () => {
    fixture.detectChanges();
    billingSpy.createInvoice.and.returnValues(
      throwError(() => ({ status: 409, error: { message: 'Already invoiced RHA-12' } })),
      of(mockInvoice()),
    );
    const confirm = spyOn(globalThis, 'confirm').and.returnValue(true);
    component.openCreate();
    component.submitForm();
    expect(confirm).toHaveBeenCalled();
    expect(billingSpy.createInvoice).toHaveBeenCalledTimes(2);
    expect(billingSpy.createInvoice.calls.mostRecent().args[0].supplementary).toBeTrue();
    expect(billingSpy.createInvoice.calls.first().args[0].supplementary).toBeUndefined();
  });

  it('QA D529: declining the supplementary invoice creates nothing more', () => {
    fixture.detectChanges();
    billingSpy.createInvoice.and.returnValue(
      throwError(() => ({ status: 409, error: { message: 'Already invoiced RHA-12' } })),
    );
    spyOn(globalThis, 'confirm').and.returnValue(false);
    component.openCreate();
    component.submitForm();
    expect(billingSpy.createInvoice).toHaveBeenCalledTimes(1);
    expect(component.saving()).toBeFalse();
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

  it('QA D1059: the KPI tiles follow the date filter', () => {
    billingSpy.getSummary.and.returnValue(
      of({ total: 3, paid: 1, outstanding: 2, overdue: 0, totalRevenue: 1500, balance: 6000 }),
    );
    billingSpy.searchInvoices.and.returnValue(of(mockPage([])));
    fixture.detectChanges();
    component.filterFromDate.set('2026-10-29');
    component.filterToDate.set('2026-10-29');
    component.applyServerFilters();
    expect(billingSpy.getSummary).toHaveBeenCalledWith({
      fromDate: '2026-10-29',
      toDate: '2026-10-29',
    });
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
    component.downloadPdf(mockInvoice());
    expect(toastSpy.error).toHaveBeenCalled();
  });

  describe('document names (QA D1009)', () => {
    function pdf(disposition?: string): HttpResponse<Blob> {
      const headers = disposition
        ? new HttpHeaders({ 'Content-Disposition': disposition })
        : new HttpHeaders();
      return new HttpResponse({ body: new Blob(['%PDF']), headers });
    }

    let saved: string[];
    beforeEach(() => {
      saved = [];
      spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
      spyOn(URL, 'revokeObjectURL');
      spyOn(HTMLAnchorElement.prototype, 'click').and.callFake(function (this: HTMLAnchorElement) {
        saved.push(this.download);
      });
    });

    it('names the invoice PDF by the name the server gives, never by its id', () => {
      billingSpy.getInvoicePdf.and.returnValue(
        of(pdf('attachment; filename="invoice-GH-2026-000012.pdf"')),
      );
      component.downloadPdf(mockInvoice({ id: '7b0c1f1e-uuid', invoiceNumber: 'GH-2026-000012' }));
      expect(saved).toEqual(['invoice-GH-2026-000012.pdf']);
    });

    it('falls back to the invoice number when the header is not readable', () => {
      billingSpy.getInvoicePdf.and.returnValue(of(pdf()));
      component.downloadPdf(mockInvoice({ id: '7b0c1f1e-uuid', invoiceNumber: 'GH-2026-000012' }));
      expect(saved[0]).toBe('invoice-GH-2026-000012.pdf');
      expect(saved[0]).not.toContain('7b0c1f1e');
    });

    it('gives each receipt of an invoice its own name', () => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      const row = { id: 'p1', amount: 500, paymentDate: '2026-10-18', paymentMethod: 'CASH' };
      billingSpy.getReceiptPdf.and.returnValues(
        of(pdf('attachment; filename="INV-0001-R1.pdf"')),
        of(pdf('attachment; filename="INV-0001-R2.pdf"')),
      );
      component.downloadReceipt({ ...row, status: 'CONFIRMED' } as never);
      component.downloadReceipt({ ...row, id: 'p2', status: 'CONFIRMED' } as never);
      expect(saved).toEqual(['INV-0001-R1.pdf', 'INV-0001-R2.pdf']);
    });
  });

  describe('check-in copay still standing (QA D936)', () => {
    const copay = mockInvoice({
      id: 'cp-5',
      invoiceNumber: '000005',
      status: 'PAID',
      amountPaid: 1000,
      checkInCopay: true,
      encounterCode: 'ENC-20261027-25DB1D',
    });

    it('lists the open copay of the picked patient and links its visit in one click', () => {
      fixture.detectChanges();
      component.openCreate();
      billingSpy.searchInvoices.and.returnValue(
        of(
          mockPage([
            copay,
            mockInvoice({
              id: 'cp-4',
              checkInCopay: true,
              status: 'PAID',
              encounterCode: 'ENC-OLD',
              creditedToInvoiceNumber: '000003',
            }),
            mockInvoice({ id: 'v-1', status: 'PAID', encounterCode: 'ENC-X' }),
          ]),
        ),
      );
      component.onPatientPicked({ id: 'pat-1', firstName: 'Fatimata', lastName: 'S' } as never);
      expect(billingSpy.searchInvoices.calls.mostRecent().args[0]).toEqual({
        patientId: 'pat-1',
        statuses: ['PAID'],
      });
      expect(component.openCopays().map((c) => c.id)).toEqual(['cp-5']);
      // The only open copay is proposed; the clerk clears it, then links it again in one click.
      expect(component.form.encounterReference).toBe('ENC-20261027-25DB1D');
      component.form = { ...component.form, encounterReference: undefined };

      fixture.detectChanges();
      const notice: HTMLElement = fixture.nativeElement.querySelector(
        '[data-testid="open-copays"]',
      );
      expect(notice).not.toBeNull();
      notice
        .querySelector<HTMLButtonElement>('[data-testid="link-copay-ENC-20261027-25DB1D"]')!
        .click();
      expect(component.form.encounterReference).toBe('ENC-20261027-25DB1D');
    });

    it('proposes the visit of the only open copay, which the clerk may clear', () => {
      fixture.detectChanges();
      component.openCreate();
      billingSpy.searchInvoices.and.returnValue(of(mockPage([copay])));
      component.onPatientPicked({ id: 'pat-1' } as never);
      expect(component.form.encounterReference).toBe('ENC-20261027-25DB1D');
    });

    it('does not carry the proposed visit to another patient', () => {
      fixture.detectChanges();
      component.openCreate();
      billingSpy.searchInvoices.and.returnValue(of(mockPage([copay])));
      component.onPatientPicked({ id: 'pat-1' } as never);
      billingSpy.searchInvoices.and.returnValue(of(mockPage([])));
      component.onPatientPicked({ id: 'pat-2' } as never);
      expect(component.form.encounterReference).toBeUndefined();
    });

    it('lists nothing when the copays were already taken', () => {
      fixture.detectChanges();
      component.openCreate();
      billingSpy.searchInvoices.and.returnValue(
        of(mockPage([{ ...copay, creditedToInvoiceNumber: '000009' }])),
      );
      component.onPatientPicked({ id: 'pat-1' } as never);
      expect(component.openCopays()).toEqual([]);
    });
  });

  describe('payment reference (QA D1007)', () => {
    it('is required for the methods the server refuses without one', () => {
      fixture.detectChanges();
      component.openPayment(component.invoices()[0]);
      component.paymentMethod.set('MOBILE_MONEY');
      component.paymentAmount.set(500);
      component.paymentReference.set('');
      component.recordPayment();
      expect(billingSpy.recordPayment).not.toHaveBeenCalled();
      expect(toastSpy.error).toHaveBeenCalledWith('BILLING.PAYMENT_REFERENCE_REQUIRED');
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('label[for="payment-reference"]').textContent,
      ).toContain('*');
      component.paymentMethod.set('CASH');
      fixture.detectChanges();
      expect(component.paymentReferenceRequired()).toBeFalse();
    });
  });

  describe('discount, waiver and credit note (QA D974)', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      fixture.detectChanges();
    });

    it('refuses an adjustment without a reason', () => {
      component.adjustmentType.set('WAIVER');
      component.adjustmentAmount.set(1000);
      component.submitAdjustment();
      expect(billingSpy.addAdjustment).not.toHaveBeenCalled();
      expect(toastSpy.error).toHaveBeenCalledWith('BILLING.ADJUSTMENT_REASON_REQUIRED');
    });

    it('a credit note names its line and may leave the amount to the server', () => {
      billingSpy.addAdjustment.and.returnValue(
        of(mockInvoice({ totalAmount: 500, balanceDue: 500 })),
      );
      component.adjustmentType.set('CREDIT_NOTE');
      component.adjustmentReason.set('NFS non réalisée');
      component.submitAdjustment();
      expect(billingSpy.addAdjustment).not.toHaveBeenCalled();
      component.adjustmentLineId.set('item-9');
      component.submitAdjustment();
      expect(billingSpy.addAdjustment).toHaveBeenCalledWith('inv-1', {
        type: 'CREDIT_NOTE',
        amount: null,
        reason: 'NFS non réalisée',
        invoiceItemId: 'item-9',
      });
      expect(billingSpy.listAdjustments).toHaveBeenCalledWith('inv-1');
    });

    it('shows the form on an issued invoice only', () => {
      expect(
        fixture.nativeElement.querySelector('[data-testid="adjustment-submit"]'),
      ).not.toBeNull();
      expect(component.canAdjustInvoice(mockInvoice({ status: 'DRAFT' }))).toBeFalse();
    });
  });

  it('an invoice settled by a waiver can be cancelled (QA D974)', () => {
    component.openEdit(
      mockInvoice({ status: 'PAID', amountPaid: 0, totalAmount: 0, balanceDue: 0 }),
    );
    expect(component.statusOptions()).toEqual(['PAID', 'CANCELLED']);
    expect(component.statusLocked()).toBeFalse();
    component.openEdit(mockInvoice({ status: 'PAID', amountPaid: 1000 }));
    expect(component.statusOptions()).toEqual(['PAID']);
  });

  describe('cancelling an issued invoice (QA D1069)', () => {
    it('asks for the reason, refuses without one, and sends it', () => {
      billingSpy.updateInvoice.and.returnValue(of(mockInvoice({ status: 'CANCELLED' })));
      component.openEdit(mockInvoice({ status: 'SENT' }));
      component.form.status = 'CANCELLED';
      expect(component.cancellingIssued()).toBeTrue();
      component.submitForm();
      expect(billingSpy.updateInvoice).not.toHaveBeenCalled();
      expect(toastSpy.error).toHaveBeenCalledWith('BILLING.CANCEL_REASON_REQUIRED');
      component.form.cancelReason = 'Facturé au mauvais patient';
      component.submitForm();
      expect(billingSpy.updateInvoice.calls.mostRecent().args[1].cancelReason).toBe(
        'Facturé au mauvais patient',
      );
    });

    it('marks and describes the missing reason under its field (QA D1072)', () => {
      component.openEdit(mockInvoice({ status: 'SENT' }));
      component.form.status = 'CANCELLED';
      fixture.detectChanges();
      component.submitForm();
      fixture.detectChanges();
      const field: HTMLElement = fixture.nativeElement.querySelector('#bill-cancel-reason');
      expect(field.getAttribute('aria-invalid')).toBe('true');
      const message = fixture.nativeElement.querySelector('#bill-cancel-reason-invalid-msg');
      expect(message?.textContent).toBe('BILLING.CANCEL_REASON_REQUIRED');
      expect(field.getAttribute('aria-describedby')).toContain('bill-cancel-reason-invalid-msg');
    });

    it('a waiver-settled invoice is cancelled with a reason too', () => {
      component.openEdit(
        mockInvoice({ status: 'PAID', amountPaid: 0, totalAmount: 0, balanceDue: 0 }),
      );
      component.form.status = 'CANCELLED';
      expect(component.cancellingIssued()).toBeTrue();
    });

    it('a draft, or an edit that does not cancel, sends no reason', () => {
      billingSpy.updateInvoice.and.returnValue(of(mockInvoice()));
      component.openEdit(mockInvoice({ status: 'DRAFT' }));
      component.form.status = 'CANCELLED';
      expect(component.cancellingIssued()).toBeFalse();
      component.openEdit(mockInvoice({ status: 'SENT' }));
      component.form.cancelReason = 'left over';
      component.submitForm();
      expect(billingSpy.updateInvoice.calls.mostRecent().args[1].cancelReason).toBeUndefined();
    });

    it('shows the recorded reason on the cancelled invoice', () => {
      fixture.detectChanges();
      component.viewInvoice(
        mockInvoice({
          status: 'CANCELLED',
          cancelReason: 'Doublon',
          cancelledAt: '2026-10-30T17:09:00',
        }),
      );
      fixture.detectChanges();
      const reason = fixture.nativeElement.querySelector('[data-testid="cancel-reason"]');
      expect(reason?.textContent).toContain('Doublon');
    });
  });

  describe('a zero-priced line is priced in place (QA D1088)', () => {
    const zeroLine = {
      id: 'line-0',
      billingInvoiceId: 'inv-1',
      itemDescription: 'Radiographie thoracique',
      itemCategory: 'SERVICE',
      quantity: 1,
      unitPrice: 0,
      totalPrice: 0,
    };

    beforeEach(() => {
      billingSpy.getInvoiceItems.and.returnValue(of([zeroLine]));
      billingSpy.getInvoice.and.returnValue(
        of(mockInvoice({ status: 'DRAFT', totalAmount: 7500 })),
      );
      fixture.detectChanges();
      component.viewInvoice(mockInvoice({ status: 'DRAFT', totalAmount: 0, balanceDue: 0 }));
      fixture.detectChanges();
    });

    it('offers to edit each line of a draft, not only to delete it', () => {
      expect(
        fixture.nativeElement.querySelector('[data-testid="line-edit-line-0"]'),
      ).not.toBeNull();
    });

    it('updates the same line with its new price and re-reads the total', () => {
      billingSpy.updateInvoiceItem.and.returnValue(
        of({ ...zeroLine, unitPrice: 7500, totalPrice: 7500 }),
      );
      component.startEditLine(zeroLine);
      expect(component.editLineUnitPrice()).toBeNull();
      expect(component.canSaveLine()).toBeFalse();
      component.editLineUnitPrice.set(7500);
      component.saveLine(zeroLine);
      expect(billingSpy.updateInvoiceItem).toHaveBeenCalledWith('line-0', {
        billingInvoiceId: 'inv-1',
        itemDescription: 'Radiographie thoracique',
        itemCategory: 'SERVICE',
        quantity: 1,
        unitPrice: 7500,
      });
      expect(billingSpy.createInvoiceItem).not.toHaveBeenCalled();
      expect(billingSpy.deleteInvoiceItem).not.toHaveBeenCalled();
      expect(component.editingLineId()).toBeNull();
    });
  });

  describe('an empty amount asks for an amount (QA D1089)', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
    });

    it('a waiver with no amount is not told about decimals', () => {
      component.adjustmentType.set('WAIVER');
      component.adjustmentReason.set('Indigent');
      component.adjustmentAmount.set(null);
      component.submitAdjustment();
      expect(billingSpy.addAdjustment).not.toHaveBeenCalled();
      expect(toastSpy.error).toHaveBeenCalledWith('BILLING.ADJUSTMENT_AMOUNT_REQUIRED');
      expect(toastSpy.error).not.toHaveBeenCalledWith('BILLING.PAYMENT_WHOLE_AMOUNT');
    });

    it('a waiver with decimals is told about decimals', () => {
      component.adjustmentType.set('DISCOUNT');
      component.adjustmentReason.set('Geste commercial');
      component.adjustmentAmount.set(250.5);
      component.submitAdjustment();
      expect(toastSpy.error).toHaveBeenCalledWith('BILLING.PAYMENT_WHOLE_AMOUNT');
    });

    it('a payment with no amount asks for one', () => {
      component.paymentInv.set(mockInvoice());
      component.paymentAmount.set(null);
      component.recordPayment();
      expect(toastSpy.error).toHaveBeenCalledWith('BILLING.PAYMENT_AMOUNT_REQUIRED');
    });
  });

  describe('no dead button on a reason or an amount (QA D1069 / D1089, round 19)', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.viewInvoice(component.invoices()[0]);
      fixture.detectChanges();
    });

    it('QA D1069: the payment reversal is refused where the reason is typed, never by a dead button', () => {
      component.openReversal(
        {
          id: 'p1',
          amount: 1000,
          paymentDate: '2026-10-18',
          paymentMethod: 'CASH',
          status: 'CONFIRMED',
        },
        'reverse',
      );
      fixture.detectChanges();
      const host: HTMLElement = fixture.nativeElement;
      const confirm = Array.from(
        host.querySelectorAll<HTMLButtonElement>('.modal-actions .btn-primary'),
      ).find((b) => b.textContent?.includes('COMMON.CONFIRM'))!;
      expect(confirm.disabled).toBeFalse();
      confirm.click();
      fixture.detectChanges();
      expect(billingSpy.reversePayment).not.toHaveBeenCalled();
      expect(host.querySelector('#reversal-reason')!.getAttribute('aria-invalid')).toBe('true');
      expect(host.textContent).toContain('BILLING.REVERSAL_REASON_REQUIRED');
    });

    it('QA D1089: a line with no price is refused under the price, not by a dead "Add"', () => {
      component.viewInvoice(mockInvoice({ status: 'DRAFT' }));
      fixture.detectChanges();
      component.newItemDescription.set('Consultation');
      component.newItemQuantity.set(1);
      component.newItemUnitPrice.set(null);
      fixture.detectChanges();
      const host: HTMLElement = fixture.nativeElement;
      const add = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
        b.textContent?.includes('BILLING.ADD_LINE'),
      )!;
      expect(add.disabled).toBeFalse();
      add.click();
      fixture.detectChanges();
      expect(billingSpy.createInvoiceItem).not.toHaveBeenCalled();
      expect(host.querySelector('#line-price')!.getAttribute('aria-invalid')).toBe('true');
      expect(host.textContent).toContain('BILLING.PAYMENT_AMOUNT_REQUIRED');
    });
  });

  describe('undoing a mistaken adjustment (QA D1105)', () => {
    const waiver = {
      id: 'adj-1',
      type: 'WAIVER' as const,
      amount: 2500,
      reason: 'Gratuité',
      createdAt: '2026-10-30T10:00:00',
    };

    beforeEach(() => {
      billingSpy.listAdjustments.and.returnValue(of([waiver]));
      fixture.detectChanges();
      component.viewInvoice(mockInvoice({ status: 'PAID', amountPaid: 1500, totalAmount: 1500 }));
      fixture.detectChanges();
    });

    it('offers the undo on a paid invoice whose copay is real money', () => {
      expect(
        fixture.nativeElement.querySelector('[data-testid="adjustment-reverse-adj-1"]'),
      ).not.toBeNull();
    });

    it('asks why, refuses without a reason, then sends it and re-reads the ledger', () => {
      billingSpy.reverseAdjustment.and.returnValue(
        of(mockInvoice({ status: 'PARTIALLY_PAID', amountPaid: 1500, totalAmount: 4000 })),
      );
      component.openAdjustmentReversal(waiver);
      fixture.detectChanges();
      const host: HTMLElement = fixture.nativeElement;
      const confirm = host.querySelector<HTMLButtonElement>(
        '[data-testid="adjustment-reversal-confirm"]',
      )!;
      expect(confirm.disabled).toBeFalse();
      confirm.click();
      fixture.detectChanges();
      expect(billingSpy.reverseAdjustment).not.toHaveBeenCalled();
      expect(host.querySelector('#adj-reversal-reason')!.getAttribute('aria-invalid')).toBe('true');

      component.adjustmentReversalReason.set('  Exonération appliquée par erreur ');
      component.submitAdjustmentReversal();
      expect(billingSpy.reverseAdjustment).toHaveBeenCalledWith(
        'inv-1',
        'adj-1',
        'Exonération appliquée par erreur',
      );
      expect(component.reversingAdjustment()).toBeNull();
      expect(billingSpy.listAdjustments).toHaveBeenCalledWith('inv-1');
    });

    it('a reversed adjustment shows its reason and no second undo', () => {
      billingSpy.listAdjustments.and.returnValue(
        of([{ ...waiver, reversedAt: '2026-10-31T09:00:00', reversalReason: 'Erreur' }]),
      );
      component.viewInvoice(mockInvoice({ status: 'PARTIALLY_PAID' }));
      fixture.detectChanges();
      const host: HTMLElement = fixture.nativeElement;
      expect(host.querySelector('[data-testid="adjustment-reversed-adj-1"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="adjustment-reverse-adj-1"]')).toBeNull();
      expect(host.textContent).toContain('Erreur');
    });
  });

  describe('receptionist (QA D973)', () => {
    it('may record a payment but not reverse one', async () => {
      TestBed.resetTestingModule();
      const roleCtx = {
        hasAnyActiveRole: (roles: string[]) => roles.includes('ROLE_RECEPTIONIST'),
      } as unknown as RoleContextService;
      const permissionSpy = jasmine.createSpyObj('PermissionService', ['hasAnyPermission']);
      permissionSpy.hasAnyPermission.and.returnValue(false);
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
      const reception = TestBed.createComponent(BillingComponent).componentInstance;
      expect(reception.canRecordPayment).toBeTrue();
      expect(reception.canReversePayment).toBeFalse();
    });
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
      billingSpy.getReceiptPdf.and.returnValue(of(new HttpResponse({ body: new Blob(['%PDF']) })));
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
