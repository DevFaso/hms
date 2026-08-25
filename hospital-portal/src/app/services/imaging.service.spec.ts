import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { ImagingService, ImagingReportResponse } from './imaging.service';

describe('ImagingService — results', () => {
  let service: ImagingService;
  let httpMock: HttpTestingController;

  const report = {
    id: 'rep-1',
    imagingOrderId: 'ord-1',
    reportStatus: 'FINAL',
    modality: 'CT',
  } as ImagingReportResponse;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ImagingService],
    });
    service = TestBed.inject(ImagingService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches a report by id', () => {
    let result: ImagingReportResponse | undefined;
    service.getReport('rep-1').subscribe((r) => (result = r));
    httpMock.expectOne('/imaging/results/rep-1').flush(report);
    expect(result?.id).toBe('rep-1');
  });

  it('fetches the latest report for an order', () => {
    service.getLatestReportByOrder('ord-1').subscribe();
    const req = httpMock.expectOne('/imaging/results/order/ord-1');
    expect(req.request.method).toBe('GET');
    req.flush(report);
  });

  it('fetches all report versions for an order', () => {
    service.getReportsForOrder('ord-1').subscribe();
    httpMock.expectOne('/imaging/results/order/ord-1/all').flush([report]);
  });

  it('fetches hospital reports with status and modality filters', () => {
    service.getReportsByHospital('h-1', { status: 'PRELIMINARY', modality: 'MRI' }).subscribe();

    const req = httpMock.expectOne((r) => r.url === '/imaging/results/hospital/h-1');
    expect(req.request.params.get('status')).toBe('PRELIMINARY');
    expect(req.request.params.get('modality')).toBe('MRI');
    req.flush([report]);
  });

  it('fetches hospital reports without filters', () => {
    service.getReportsByHospital('h-1').subscribe();

    const req = httpMock.expectOne((r) => r.url === '/imaging/results/hospital/h-1');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([report]);
  });

  it('voids a report with a reason and carries no client-asserted identity', () => {
    service
      .updateReportStatus('rep-1', {
        status: 'CANCELLED',
        statusReason: 'study repeated',
      })
      .subscribe();

    const req = httpMock.expectOne('/imaging/results/rep-1/status');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.status).toBe('CANCELLED');
    expect(req.request.body.statusReason).toBe('study repeated');
    // The server resolves the actor; a staff id must never travel again.
    expect(req.request.body.changedByStaffId).toBeUndefined();
    req.flush({ ...report, reportStatus: 'CANCELLED' });
  });

  it('acknowledges a critical report without naming the acknowledging clinician', () => {
    service.acknowledgeCriticalReport('rep-1').subscribe();

    const req = httpMock.expectOne(
      (r) => r.url === '/imaging/results/rep-1/acknowledge-critical' && r.method === 'PUT',
    );
    expect(req.request.params.keys().length).toBe(0);
    req.flush(report);
  });

  it('authors a report against an order', () => {
    service
      .createReport({ imagingOrderId: 'ord-1', impression: 'No acute finding.' })
      .subscribe();

    const req = httpMock.expectOne('/imaging/results');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.imagingOrderId).toBe('ord-1');
    req.flush(report);
  });

  it('revises an unsigned report', () => {
    service.updateReport('rep-1', { findings: 'revised' }).subscribe();

    const req = httpMock.expectOne('/imaging/results/rep-1');
    expect(req.request.method).toBe('PUT');
    req.flush(report);
  });

  it('signs a report with no body — identity comes from the session', () => {
    service.signReport('rep-1').subscribe();

    const req = httpMock.expectOne('/imaging/results/rep-1/sign');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ ...report, reportStatus: 'FINAL', signed: true });
  });
});
