import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { PrescriptionService, CommunityPharmacyService } from './prescription.service';

describe('PrescriptionService.dispatchSms', () => {
  let service: PrescriptionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PrescriptionService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PrescriptionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs to /prescriptions/{id}/dispatch-sms with pharmacyId and note', (done) => {
    service.dispatchSms('rx-1', 'pharm-2', 'priority').subscribe((res) => {
      expect(res.status).toBe('SENT');
      expect(res.pharmacyName).toBe('Pharmacie Centrale');
      done();
    });

    const req = httpMock.expectOne('/prescriptions/rx-1/dispatch-sms');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ pharmacyId: 'pharm-2', note: 'priority' });
    req.flush({
      data: {
        prescriptionId: 'rx-1',
        transmissionId: 't-1',
        pharmacyId: 'pharm-2',
        pharmacyName: 'Pharmacie Centrale',
        destinationPhone: '+22670111222',
        status: 'SENT',
        dispatchedAt: '2026-05-02T10:00:00',
      },
    });
  });

  it('sends note: null when note is omitted', (done) => {
    service.dispatchSms('rx-1', 'pharm-2').subscribe(() => done());

    const req = httpMock.expectOne('/prescriptions/rx-1/dispatch-sms');
    expect(req.request.body).toEqual({ pharmacyId: 'pharm-2', note: null });
    req.flush({
      data: {
        prescriptionId: 'rx-1',
        transmissionId: 't-1',
        pharmacyId: 'pharm-2',
        pharmacyName: 'Pharmacie Centrale',
        destinationPhone: '+22670111222',
        status: 'SENT',
        dispatchedAt: '2026-05-02T10:00:00',
      },
    });
  });
});

describe('CommunityPharmacyService', () => {
  let service: CommunityPharmacyService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CommunityPharmacyService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CommunityPharmacyService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs /pharmacies/community with hospitalId param', (done) => {
    service.list('h1').subscribe((list) => {
      expect(list.length).toBe(1);
      done();
    });

    const req = httpMock.expectOne((r) => r.url === '/pharmacies/community');
    expect(req.request.params.get('hospitalId')).toBe('h1');
    req.flush([
      {
        id: 'p1',
        name: 'Pharmacie X',
        phoneNumber: '+22670111',
        pharmacyType: 'COMMUNITY_PHARMACY',
      },
    ]);
  });
});
