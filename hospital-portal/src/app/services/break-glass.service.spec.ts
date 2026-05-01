import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import {
  BreakGlassService,
  BreakGlassSession,
  BreakGlassDeclareRequest,
} from './break-glass.service';

describe('BreakGlassService', () => {
  let service: BreakGlassService;
  let httpMock: HttpTestingController;

  const baseSession: BreakGlassSession = {
    id: 's1',
    patientId: 'p1',
    userId: 'u1',
    userName: 'dr.alice',
    hospitalId: 'h1',
    hospitalName: 'City Clinic',
    reason: 'Trauma override',
    startedAt: '2026-04-30T10:00:00',
    expiresAt: '2026-04-30T14:00:00',
    revokedAt: null,
    revokedByUserId: null,
    revokeReason: null,
    auditCount: 0,
    live: true,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [BreakGlassService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BreakGlassService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs to /break-glass when declaring a session', (done) => {
    const body: BreakGlassDeclareRequest = {
      patientId: 'p1',
      hospitalId: 'h1',
      reason: 'Unconscious patient, needs allergy lookup.',
      ttlMinutes: 60,
    };
    service.declare(body).subscribe((result) => {
      expect(result.id).toBe('s1');
      expect(result.live).toBe(true);
      done();
    });

    const req = httpMock.expectOne('/break-glass');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(baseSession);
  });

  it('returns null from findMyLiveSession when the server replies 204', (done) => {
    service.findMyLiveSession('p1').subscribe((result) => {
      expect(result).toBeNull();
      done();
    });

    const req = httpMock.expectOne((r) => r.url === '/break-glass/me');
    expect(req.request.method).toBe('GET');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('returns the body from findMyLiveSession when the server replies 200', (done) => {
    service.findMyLiveSession('p1').subscribe((result) => {
      expect(result?.id).toBe('s1');
      done();
    });

    const req = httpMock.expectOne((r) => r.url === '/break-glass/me');
    req.flush(baseSession);
  });

  it('passes pagination params to the audit endpoint', (done) => {
    service.listForHospital('h1', 2, 25).subscribe(() => done());

    const req = httpMock.expectOne(
      (r) =>
        r.url === '/break-glass/audit' &&
        r.params.get('hospitalId') === 'h1' &&
        r.params.get('page') === '2' &&
        r.params.get('size') === '25',
    );
    req.flush({ content: [], totalElements: 0, totalPages: 0 });
  });

  it('falls back to null on findMyLiveSession HTTP errors instead of throwing', (done) => {
    service.findMyLiveSession('p1').subscribe((result) => {
      expect(result).toBeNull();
      done();
    });

    const req = httpMock.expectOne((r) => r.url === '/break-glass/me');
    req.flush({ message: 'forbidden' }, { status: 403, statusText: 'Forbidden' });
  });

  it('POSTs to /break-glass/{id}/revoke', (done) => {
    service.revoke('s1', { reason: 'Patient consented.' }).subscribe(() => done());

    const req = httpMock.expectOne('/break-glass/s1/revoke');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Patient consented.' });
    req.flush({ ...baseSession, revokedAt: '2026-04-30T10:30:00', live: false });
  });
});
