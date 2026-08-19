import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ObgynReferralService } from './obgyn-referral.service';

describe('ObgynReferralService', () => {
  let service: ObgynReferralService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ObgynReferralService],
    });
    service = TestBed.inject(ObgynReferralService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a new referral', () => {
    service
      .create({
        patientId: 'p1',
        hospitalId: 'h1',
        careContext: 'ANTENATAL',
        referralReason: 'High-risk pregnancy',
        urgency: 'URGENT',
        transferType: 'CONSULTATION',
        ongoingMidwiferyCare: true,
        generateLetter: true,
      })
      .subscribe();
    const req = httpMock.expectOne('/referrals/obgyn');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.urgency).toBe('URGENT');
    req.flush({ id: 'r1', status: 'SUBMITTED' });
  });

  it('GETs referrals assigned to an OB/GYN user', () => {
    service.assignedTo('u1', 0, 20).subscribe((page) => expect(page.content.length).toBe(1));
    const req = httpMock.expectOne('/referrals/obgyn/assigned/u1?page=0&size=20');
    expect(req.request.method).toBe('GET');
    req.flush({
      content: [{ id: 'r1', status: 'SUBMITTED' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    });
  });

  it('POSTs acknowledge with obgynUserId and planSummary', () => {
    service.acknowledge('r1', 'u1', 'Will review within 24h').subscribe();
    const req = httpMock.expectOne('/referrals/obgyn/r1/acknowledge');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ obgynUserId: 'u1', planSummary: 'Will review within 24h' });
    req.flush({ id: 'r1', status: 'ACKNOWLEDGED' });
  });

  it('POSTs start without a body', () => {
    service.start('r1').subscribe((r) => expect(r.status).toBe('IN_PROGRESS'));
    const req = httpMock.expectOne('/referrals/obgyn/r1/start');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ id: 'r1', status: 'IN_PROGRESS' });
  });

  it('POSTs complete with planSummary and updateCareTeam', () => {
    service.complete('r1', 'Plan done', true).subscribe();
    const req = httpMock.expectOne('/referrals/obgyn/r1/complete');
    expect(req.request.body).toEqual({ planSummary: 'Plan done', updateCareTeam: true });
    req.flush({ id: 'r1', status: 'COMPLETED' });
  });

  it('POSTs cancel with a reason', () => {
    service.cancel('r1', 'No longer needed').subscribe();
    const req = httpMock.expectOne('/referrals/obgyn/r1/cancel');
    expect(req.request.body).toEqual({ reason: 'No longer needed' });
    req.flush({ id: 'r1', status: 'CANCELLED' });
  });

  it('POSTs a message with an empty attachments list', () => {
    service.postMessage('r1', 'Hello').subscribe((m) => expect(m.body).toBe('Hello'));
    const req = httpMock.expectOne('/referrals/obgyn/r1/messages');
    expect(req.request.body).toEqual({ body: 'Hello', attachments: [] });
    req.flush({ id: 'm1', senderUserId: 'u1', body: 'Hello', read: false, sentAt: '2026-08-19' });
  });

  it('GETs the status summary report', () => {
    service.summary().subscribe((s) => expect(s.overdue).toBe(2));
    httpMock.expectOne('/referrals/obgyn/reports/summary').flush({
      submitted: 4,
      acknowledged: 1,
      inProgress: 2,
      completed: 9,
      cancelled: 1,
      overdue: 2,
    });
  });
});
