import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { DischargeService, DischargeApproval, DischargeSummary } from './discharge.service';

describe('DischargeService', () => {
  let service: DischargeService;
  let httpMock: HttpTestingController;

  const approval = { id: 'a-1', status: 'PENDING', patientName: 'Jane Doe' } as DischargeApproval;
  const summary = { id: 's-1', patientName: 'Jane Doe', isFinalized: false } as DischargeSummary;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), DischargeService],
    });
    service = TestBed.inject(DischargeService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('requests a discharge approval', () => {
    let result: DischargeApproval | undefined;
    service
      .requestApproval({
        registrationId: 'r-1',
        nurseStaffId: 'n-1',
        nurseAssignmentId: 'na-1',
        nurseSummary: 'Stable for discharge',
      })
      .subscribe((r) => (result = r));

    const req = httpMock.expectOne('/discharge-approvals');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.registrationId).toBe('r-1');
    req.flush(approval);
    expect(result?.id).toBe('a-1');
  });

  it('approves with a doctor decision payload', () => {
    service
      .approve('a-1', { doctorStaffId: 'd-1', doctorAssignmentId: 'da-1', doctorNote: 'ok' })
      .subscribe();

    const req = httpMock.expectOne('/discharge-approvals/a-1/approve');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.doctorStaffId).toBe('d-1');
    req.flush(approval);
  });

  it('rejects with a rejection reason', () => {
    service
      .reject('a-1', {
        doctorStaffId: 'd-1',
        doctorAssignmentId: 'da-1',
        rejectionReason: 'Not ready',
      })
      .subscribe();

    const req = httpMock.expectOne('/discharge-approvals/a-1/reject');
    expect(req.request.body.rejectionReason).toBe('Not ready');
    req.flush(approval);
  });

  it('cancels with staffId and optional reason as query params', () => {
    service.cancel('a-1', 'n-1', 'changed condition').subscribe();

    const req = httpMock.expectOne(
      (r) => r.url === '/discharge-approvals/a-1/cancel' && r.method === 'POST',
    );
    expect(req.request.params.get('staffId')).toBe('n-1');
    expect(req.request.params.get('reason')).toBe('changed condition');
    req.flush(approval);
  });

  it('lists approvals by hospital with a status filter', () => {
    service.approvalsByHospital('h-1', 'PENDING').subscribe();

    const req = httpMock.expectOne((r) => r.url === '/discharge-approvals/hospital/h-1');
    expect(req.request.params.get('status')).toBe('PENDING');
    req.flush([approval]);
  });

  it('lists approvals by hospital without a status filter', () => {
    service.approvalsByHospital('h-1').subscribe();

    const req = httpMock.expectOne((r) => r.url === '/discharge-approvals/hospital/h-1');
    expect(req.request.params.has('status')).toBeFalse();
    req.flush([approval]);
  });

  it('resolves an active registration for a patient', () => {
    service.findActiveRegistration('p-1', 'h-1').subscribe();

    const req = httpMock.expectOne((r) => r.url === '/registrations');
    expect(req.request.params.get('patientId')).toBe('p-1');
    expect(req.request.params.get('hospitalId')).toBe('h-1');
    expect(req.request.params.get('active')).toBe('true');
    req.flush({ content: [{ id: 'r-1' }] });
  });

  it('creates a discharge summary', () => {
    service
      .createSummary({
        patientId: 'p-1',
        encounterId: 'e-1',
        hospitalId: 'h-1',
        dischargingProviderId: 'd-1',
        assignmentId: 'da-1',
        dischargeDate: '2026-08-18',
        disposition: 'HOME',
        dischargeDiagnosis: 'Recovered',
      })
      .subscribe();

    const req = httpMock.expectOne('/discharge-summaries');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.disposition).toBe('HOME');
    req.flush(summary);
  });

  it('finalizes a summary with signature and provider as query params', () => {
    service.finalizeSummary('s-1', 'Dr. Who', 'd-1').subscribe();

    const req = httpMock.expectOne(
      (r) => r.url === '/discharge-summaries/s-1/finalize' && r.method === 'POST',
    );
    expect(req.request.params.get('providerSignature')).toBe('Dr. Who');
    expect(req.request.params.get('providerId')).toBe('d-1');
    req.flush({ ...summary, isFinalized: true });
  });

  it('loads unfinalized and pending-results worklists', () => {
    service.unfinalizedSummaries('h-1').subscribe();
    httpMock.expectOne('/discharge-summaries/hospital/h-1/unfinalized').flush([summary]);

    service.summariesWithPendingResults('h-1').subscribe();
    httpMock.expectOne('/discharge-summaries/hospital/h-1/pending-results').flush([summary]);
  });

  it('loads summaries by date range', () => {
    service.summariesByHospital('h-1', '2026-07-01', '2026-08-01').subscribe();

    const req = httpMock.expectOne((r) => r.url === '/discharge-summaries/hospital/h-1');
    expect(req.request.params.get('startDate')).toBe('2026-07-01');
    expect(req.request.params.get('endDate')).toBe('2026-08-01');
    req.flush([summary]);
  });

  it('deletes a summary with the deleting provider id', () => {
    service.deleteSummary('s-1', 'd-1').subscribe();

    const req = httpMock.expectOne(
      (r) => r.url === '/discharge-summaries/s-1' && r.method === 'DELETE',
    );
    expect(req.request.params.get('deletedByProviderId')).toBe('d-1');
    req.flush(null);
  });
});
