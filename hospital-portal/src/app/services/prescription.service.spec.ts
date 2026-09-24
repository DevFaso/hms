import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PrescriptionService } from './prescription.service';

/**
 * Wire shapes, pinned. Several gaps in this initiative existed because a
 * client posted or decoded a field name the API never had, so these assert
 * the URL and the body against
 * `PrescriptionClarificationRequestDTO.reason` and
 * `PrescriptionClarificationResolutionDTO.response`.
 */
describe('PrescriptionService — clarification endpoints', () => {
  let service: PrescriptionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(PrescriptionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the trimmed reason to /request-clarification', () => {
    service.requestClarification('rx-1', '  Dose exceeds the weight-based maximum.  ').subscribe();

    const req = http.expectOne('/prescriptions/rx-1/request-clarification');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Dose exceeds the weight-based maximum.' });
    req.flush({});
  });

  it('posts the trimmed answer to /resolve-clarification', () => {
    service.resolveClarification('rx-1', '  Dose confirmed.  ').subscribe();

    const req = http.expectOne('/prescriptions/rx-1/resolve-clarification');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ response: 'Dose confirmed.' });
    req.flush({});
  });

  it('sends no response key at all when the answer is blank', () => {
    // The backend treats the answer as optional — the doctor may have edited
    // the order instead — and an empty string is not the same as absent.
    service.resolveClarification('rx-1', '   ').subscribe();

    const req = http.expectOne('/prescriptions/rx-1/resolve-clarification');
    expect(req.request.body).toEqual({ response: undefined });
    req.flush({});
  });

  it('asks the list endpoint for the most recently touched first', () => {
    // Without a sort the default page of 20 is an arbitrary slice, so a
    // prescription awaiting clarification can be unreachable on the page
    // that carries the only control for answering it.
    service.list().subscribe();

    const req = http.expectOne((r) => r.url === '/prescriptions');
    expect(req.request.params.get('sort')).toBe('updatedAt,desc');
    req.flush({ content: [] });
  });
});
