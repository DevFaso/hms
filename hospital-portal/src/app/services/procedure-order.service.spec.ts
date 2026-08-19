import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ProcedureOrderService } from './procedure-order.service';

describe('ProcedureOrderService', () => {
  let service: ProcedureOrderService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ProcedureOrderService],
    });
    service = TestBed.inject(ProcedureOrderService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new procedure order', () => {
    service
      .create({
        patientId: 'p1',
        hospitalId: 'h1',
        procedureName: 'Colonoscopy',
        indication: 'Screening',
        urgency: 'ROUTINE',
      })
      .subscribe((o) => expect(o.status).toBe('ORDERED'));
    const req = httpMock.expectOne('/procedure-orders');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'po1', patientId: 'p1', hospitalId: 'h1', status: 'ORDERED' });
  });

  it('GETs hospital orders with an optional status filter', () => {
    service.byHospital('h1', 'SCHEDULED').subscribe((list) => expect(list.length).toBe(1));
    const req = httpMock.expectOne('/procedure-orders/hospital/h1?status=SCHEDULED');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'po1', patientId: 'p1', hospitalId: 'h1', status: 'SCHEDULED' }]);
  });

  it('GETs the pending-consent worklist', () => {
    service.pendingConsent('h1').subscribe();
    httpMock.expectOne('/procedure-orders/hospital/h1/pending-consent').flush([]);
  });

  it('PUTs a partial lifecycle update', () => {
    service
      .update('po1', { status: 'SCHEDULED', scheduledDatetime: '2026-09-01T10:00' })
      .subscribe((o) => expect(o.status).toBe('SCHEDULED'));
    const req = httpMock.expectOne('/procedure-orders/po1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.scheduledDatetime).toBe('2026-09-01T10:00');
    req.flush({ id: 'po1', patientId: 'p1', hospitalId: 'h1', status: 'SCHEDULED' });
  });

  it('POSTs cancel with the reason as a query param (no body)', () => {
    service.cancel('po1', 'Patient declined').subscribe((o) => expect(o.status).toBe('CANCELLED'));
    const req = httpMock.expectOne(
      '/procedure-orders/po1/cancel?cancellationReason=Patient%20declined',
    );
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ id: 'po1', patientId: 'p1', hospitalId: 'h1', status: 'CANCELLED' });
  });
});
