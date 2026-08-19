import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { MaternityService } from './maternity.service';

describe('MaternityService', () => {
  let service: MaternityService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), MaternityService],
    });
    service = TestBed.inject(MaternityService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new maternal history', () => {
    service
      .create({ patientId: 'p1', hospitalId: 'h1', recordedDate: '2026-08-19T10:00' })
      .subscribe();
    const req = httpMock.expectOne('/maternal-history');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.patientId).toBe('p1');
    req.flush({ id: 'mh1' });
  });

  it('PUTs an update as a new version', () => {
    service
      .update('mh1', { patientId: 'p1', hospitalId: 'h1', recordedDate: '2026-08-19T10:00' })
      .subscribe();
    const req = httpMock.expectOne('/maternal-history/mh1');
    expect(req.request.method).toBe('PUT');
    req.flush({ id: 'mh1', versionNumber: 2 });
  });

  it('GETs the current history for a patient', () => {
    service.currentForPatient('p1').subscribe((h) => expect(h.id).toBe('mh1'));
    httpMock.expectOne('/maternal-history/patient/p1/current').flush({ id: 'mh1' });
  });

  it('GETs the high-risk worklist with paging params', () => {
    service.highRisk('h1', 2, 10).subscribe((page) => expect(page.totalElements).toBe(1));
    const req = httpMock.expectOne('/maternal-history/hospital/h1/high-risk?page=2&size=10');
    expect(req.request.method).toBe('GET');
    req.flush({ content: [{ id: 'mh1' }], totalElements: 1, totalPages: 1, number: 2, size: 10 });
  });

  it('builds search params only for provided filters', () => {
    service.search('h1', { riskCategory: 'HIGH', page: 0, size: 20 }).subscribe();
    const req = httpMock.expectOne(
      '/maternal-history/hospital/h1/search?riskCategory=HIGH&page=0&size=20',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });
  });

  it('POSTs mark-reviewed without a body', () => {
    service.markReviewed('mh1').subscribe();
    const req = httpMock.expectOne('/maternal-history/mh1/mark-reviewed');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ id: 'mh1', reviewedByProvider: true });
  });

  it('POSTs calculate-risk and returns the rescored record', () => {
    service.calculateRisk('mh1').subscribe((h) => expect(h.riskCategory).toBe('HIGH'));
    const req = httpMock.expectOne('/maternal-history/mh1/calculate-risk');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'mh1', calculatedRiskScore: 55, riskCategory: 'HIGH' });
  });
});
