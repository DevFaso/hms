import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { RefillApprovalService, RefillRequest } from './refill-approval.service';

describe('RefillApprovalService', () => {
  let service: RefillApprovalService;
  let httpMock: HttpTestingController;

  const sampleRefill: RefillRequest = {
    id: 'r1',
    prescriptionId: 'rx1',
    medicationName: 'Metformin 500mg',
    patientId: 'p1',
    status: 'REQUESTED',
    preferredPharmacy: 'CVS',
    notes: 'running low',
    providerNotes: null,
    requestedAt: '2026-05-01T10:00:00',
    updatedAt: '2026-05-01T10:00:00',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [RefillApprovalService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RefillApprovalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists refill requests with status filter', (done) => {
    service.list('REQUESTED', 0, 20).subscribe((page) => {
      expect(page.content.length).toBe(1);
      expect(page.content[0].status).toBe('REQUESTED');
      done();
    });

    const req = httpMock.expectOne((r) => r.url === '/refills');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('REQUESTED');
    expect(req.request.params.get('page')).toBe('0');
    req.flush({
      data: { content: [sampleRefill], totalElements: 1, totalPages: 1, size: 20, number: 0 },
    });
  });

  it('omits the status param when no filter', (done) => {
    service.list().subscribe(() => done());

    const req = httpMock.expectOne((r) => r.url === '/refills');
    expect(req.request.params.has('status')).toBeFalse();
    req.flush({
      data: { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 },
    });
  });

  it('PUTs the approve endpoint with provider notes', (done) => {
    service.approve('r1', { providerNotes: 'OK' }).subscribe((result) => {
      expect(result.id).toBe('r1');
      done();
    });

    const req = httpMock.expectOne('/refills/r1/approve');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ providerNotes: 'OK' });
    req.flush({ data: { ...sampleRefill, status: 'APPROVED', providerNotes: 'OK' } });
  });

  it('PUTs the reject endpoint with provider notes', (done) => {
    service.reject('r1', { providerNotes: 'Discontinued' }).subscribe((result) => {
      expect(result.status).toBe('DENIED');
      done();
    });

    const req = httpMock.expectOne('/refills/r1/reject');
    expect(req.request.method).toBe('PUT');
    req.flush({ data: { ...sampleRefill, status: 'DENIED', providerNotes: 'Discontinued' } });
  });

  it('returns 0 from pendingCount on HTTP error', (done) => {
    service.pendingCount().subscribe((count) => {
      expect(count).toBe(0);
      done();
    });

    const req = httpMock.expectOne('/refills/pending-count');
    req.flush(null, { status: 500, statusText: 'Server Error' });
  });

  it('returns the pendingCount value when the server returns a number', (done) => {
    service.pendingCount().subscribe((count) => {
      expect(count).toBe(7);
      done();
    });

    const req = httpMock.expectOne('/refills/pending-count');
    req.flush({ data: { pendingCount: 7 } });
  });
});
