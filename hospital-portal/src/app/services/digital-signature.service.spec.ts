import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { DigitalSignatureService } from './digital-signature.service';

describe('DigitalSignatureService', () => {
  let service: DigitalSignatureService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), DigitalSignatureService],
    });
    service = TestBed.inject(DigitalSignatureService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a sign request with staff + hospital ids', () => {
    service
      .sign({
        reportType: 'LAB_RESULT',
        reportId: 'rep-1',
        signedByStaffId: 'staff-1',
        hospitalId: 'hosp-1',
        signatureValue: 'Dr. Jane Doe',
      })
      .subscribe((res) => expect(res.status).toBe('SIGNED'));
    const req = httpMock.expectOne('/signatures/sign');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.hospitalId).toBe('hosp-1');
    req.flush({
      id: 's1',
      reportType: 'LAB_RESULT',
      reportId: 'rep-1',
      signedByStaffId: 'staff-1',
      signedByName: 'Jane Doe',
      hospitalId: 'hosp-1',
      hospitalName: 'HGY',
      signatureValue: 'Dr. Jane Doe',
      signatureDateTime: '2026-01-01T10:00:00',
      status: 'SIGNED',
    });
  });

  it('POSTs verify with the required reportType/reportId even when signatureId is set', () => {
    service
      .verify({
        reportType: 'LAB_RESULT',
        reportId: 'rep-1',
        signatureId: 's1',
        signatureValue: 'Dr. Jane Doe',
      })
      .subscribe((res) => expect(res.isValid).toBeTrue());
    const req = httpMock.expectOne('/signatures/verify');
    expect(req.request.body.reportType).toBe('LAB_RESULT');
    expect(req.request.body.reportId).toBe('rep-1');
    req.flush({ isValid: true, signatureId: 's1', message: 'ok' });
  });

  it('POSTs revoke with the revocationReason field name the backend validates', () => {
    service.revoke('s1', 'Signed in error').subscribe();
    const req = httpMock.expectOne('/signatures/s1/revoke');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ revocationReason: 'Signed in error' });
    req.flush({
      id: 's1',
      reportType: 'LAB_RESULT',
      reportId: 'rep-1',
      signedByStaffId: 'staff-1',
      signedByName: 'Jane Doe',
      hospitalId: 'hosp-1',
      hospitalName: 'HGY',
      signatureValue: 'Dr. Jane Doe',
      signatureDateTime: '2026-01-01T10:00:00',
      status: 'REVOKED',
    });
  });

  it('GETs the is-signed flag for a report', () => {
    service.isReportSigned('IMAGING_REPORT', 'rep-9').subscribe((signed) => {
      expect(signed).toBeTrue();
    });
    const req = httpMock.expectOne('/signatures/report/IMAGING_REPORT/rep-9/is-signed');
    expect(req.request.method).toBe('GET');
    req.flush(true);
  });

  it('GETs report signatures as a bare array', () => {
    service.listByReport('DISCHARGE_SUMMARY', 'rep-2').subscribe((list) => {
      expect(list.length).toBe(1);
    });
    const req = httpMock.expectOne('/signatures/report/DISCHARGE_SUMMARY/rep-2');
    req.flush([
      {
        id: 's2',
        reportType: 'DISCHARGE_SUMMARY',
        reportId: 'rep-2',
        signedByStaffId: 'staff-1',
        signedByName: 'Jane Doe',
        hospitalId: 'hosp-1',
        hospitalName: 'HGY',
        signatureValue: 'x',
        signatureDateTime: '2026-01-01T10:00:00',
        status: 'SIGNED',
      },
    ]);
  });
});
