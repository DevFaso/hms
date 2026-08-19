import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { BillingService } from './billing.service';

describe('BillingService', () => {
  let service: BillingService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), BillingService],
    });
    service = TestBed.inject(BillingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs search with body filters and query-string paging', () => {
    service
      .searchInvoices(
        { statuses: ['SENT', 'PARTIALLY_PAID'], fromDate: '2026-01-01', toDate: '2026-06-30' },
        1,
        20,
      )
      .subscribe((page) => expect(page.totalElements).toBe(1));
    const req = httpMock.expectOne('/billing-invoices/search?page=1&size=20');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      statuses: ['SENT', 'PARTIALLY_PAID'],
      fromDate: '2026-01-01',
      toDate: '2026-06-30',
    });
    req.flush({ content: [{ id: 'i1' }], totalElements: 1, totalPages: 1, number: 1, size: 20 });
  });

  it('omits empty filters from the search body', () => {
    service.searchInvoices({}, 0, 20).subscribe();
    const req = httpMock.expectOne('/billing-invoices/search?page=0&size=20');
    expect(req.request.body).toEqual({});
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
  });

  it('POSTs a payment with only the amount (backend discards the rest)', () => {
    service.recordPayment('i1', 5000).subscribe((inv) => expect(inv.status).toBe('PAID'));
    const req = httpMock.expectOne('/billing-invoices/i1/payments');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ amount: 5000 });
    req.flush({ id: 'i1', status: 'PAID', amountPaid: 5000, balanceDue: 0 });
  });

  it('POSTs email with explicit attachPdf and locale', () => {
    service
      .emailInvoice('i1', { to: ['a@x.org'], attachPdf: true, locale: 'fr' })
      .subscribe((res) => expect(res.status).toBe('SENT'));
    const req = httpMock.expectOne('/billing-invoices/i1/email');
    expect(req.request.body.attachPdf).toBeTrue();
    expect(req.request.body.locale).toBe('fr');
    req.flush({ status: 'SENT', sentAt: '2026-01-01T10:00:00Z' });
  });

  it('DELETEs with text response parsing (backend answers text/plain)', () => {
    service.deleteInvoice('i1').subscribe((msg) => expect(msg).toBe('Invoice deleted'));
    const req = httpMock.expectOne('/billing-invoices/i1');
    expect(req.request.method).toBe('DELETE');
    expect(req.request.responseType).toBe('text');
    req.flush('Invoice deleted');
  });

  it('GETs overdue with an optional reference date', () => {
    service.getOverdue('2026-08-01').subscribe((list) => expect(list.length).toBe(0));
    const req = httpMock.expectOne('/billing-invoices/overdue?referenceDate=2026-08-01');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
