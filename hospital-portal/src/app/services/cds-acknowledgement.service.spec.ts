import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { CdsAcknowledgementService } from './cds-acknowledgement.service';

describe('CdsAcknowledgementService', () => {
  let service: CdsAcknowledgementService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [CdsAcknowledgementService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CdsAcknowledgementService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs to /cds-acknowledgements when recording', (done) => {
    service
      .record({
        patientId: 'p1',
        cardSummary: 'Sepsis qSOFA ≥ 2',
        indicator: 'critical',
        action: 'OVERRIDDEN',
        reason: 'reviewed history',
      })
      .subscribe((result) => {
        expect(result.action).toBe('OVERRIDDEN');
        done();
      });

    const req = httpMock.expectOne('/cds-acknowledgements');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.action).toBe('OVERRIDDEN');
    req.flush({
      data: {
        id: 'a1',
        patientId: 'p1',
        cardSummary: 'Sepsis qSOFA ≥ 2',
        indicator: 'critical',
        action: 'OVERRIDDEN',
        reason: 'reviewed history',
        createdAt: '2026-05-02T10:00:00',
        expiresAt: '2026-05-05T10:00:00',
      },
    });
  });

  it('returns [] on error from active()', (done) => {
    service.active('p1').subscribe((list) => {
      expect(list).toEqual([]);
      done();
    });

    const req = httpMock.expectOne((r) => r.url === '/cds-acknowledgements');
    req.flush(null, { status: 500, statusText: 'Server Error' });
  });
});
