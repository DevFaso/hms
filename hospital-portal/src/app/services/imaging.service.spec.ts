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

  it('updates report status with reason and staff id', () => {
    service
      .updateReportStatus('rep-1', {
        status: 'AMENDED',
        statusReason: 'addendum requested',
        changedByStaffId: 's-1',
      })
      .subscribe();

    const req = httpMock.expectOne('/imaging/results/rep-1/status');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.status).toBe('AMENDED');
    expect(req.request.body.changedByStaffId).toBe('s-1');
    req.flush({ ...report, reportStatus: 'AMENDED' });
  });

  it('acknowledges a critical report with the staff id as query param', () => {
    service.acknowledgeCriticalReport('rep-1', 's-1').subscribe();

    const req = httpMock.expectOne(
      (r) => r.url === '/imaging/results/rep-1/acknowledge-critical' && r.method === 'PUT',
    );
    expect(req.request.params.get('acknowledgingStaffId')).toBe('s-1');
    req.flush(report);
  });
});
