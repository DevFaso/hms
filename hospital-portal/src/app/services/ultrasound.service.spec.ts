import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { UltrasoundService } from './ultrasound.service';

describe('UltrasoundService', () => {
  let service: UltrasoundService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), UltrasoundService],
    });
    service = TestBed.inject(UltrasoundService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new order', () => {
    service
      .createOrder({ patientId: 'p1', hospitalId: 'h1', scanType: 'ANATOMY_SCAN' })
      .subscribe();
    const req = httpMock.expectOne('/ultrasound/orders');
    expect(req.request.method).toBe('POST');
    req.flush({
      id: 'o1',
      patientId: 'p1',
      hospitalId: 'h1',
      scanType: 'ANATOMY_SCAN',
      status: 'ORDERED',
    });
  });

  it('POSTs cancel with the reason as a query param', () => {
    service.cancelOrder('o1', 'duplicate').subscribe();
    const req = httpMock.expectOne('/ultrasound/orders/o1/cancel?cancellationReason=duplicate');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ id: 'o1', status: 'CANCELLED' });
  });

  it('GETs hospital pending worklist', () => {
    service.pendingOrders('h1').subscribe((list) => expect(list.length).toBe(1));
    httpMock
      .expectOne('/ultrasound/orders/hospital/h1/pending')
      .flush([{ id: 'o1', status: 'ORDERED' }]);
  });

  it('POSTs a report for an order', () => {
    service
      .submitReport('o1', { scanDate: '2026-08-19', findingCategory: 'NORMAL' })
      .subscribe((r) => expect(r.id).toBe('r1'));
    const req = httpMock.expectOne('/ultrasound/orders/o1/report');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.findingCategory).toBe('NORMAL');
    req.flush({ id: 'r1' });
  });

  it('POSTs review without a body', () => {
    service.reviewReport('r1').subscribe();
    const req = httpMock.expectOne('/ultrasound/reports/r1/review');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ id: 'r1', reportReviewedByProvider: true });
  });

  it('GETs a report template', () => {
    service.template('nuchal-translucency').subscribe((t) => expect(t.scanDate).toBeDefined());
    httpMock
      .expectOne('/ultrasound/templates/nuchal-translucency')
      .flush({ scanDate: '2026-08-19', findingCategory: 'NORMAL' });
  });
});
