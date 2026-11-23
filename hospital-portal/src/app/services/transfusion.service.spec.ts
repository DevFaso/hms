import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TransfusionService } from './transfusion.service';

/**
 * QA D3352 (r71): a patient with no type and screen yet is answered 204 with no body, not a 404
 * the browser logs as an error; the service gives `null` for it.
 */
describe('TransfusionService.getCurrentBloodGroup', () => {
  let service: TransfusionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TransfusionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gives null when the patient has no group yet (204)', () => {
    let result: unknown = 'unset';
    let failed = false;
    service.getCurrentBloodGroup('p1').subscribe({
      next: (group) => (result = group),
      error: () => (failed = true),
    });
    const req = http.expectOne((r) => r.url.endsWith('/transfusions/blood-groups/patient/p1'));
    expect(req.request.method).toBe('GET');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(failed).toBeFalse();
    expect(result).toBeNull();
  });

  it('gives the group on record', () => {
    let result: unknown;
    service.getCurrentBloodGroup('p1').subscribe((group) => (result = group));
    http
      .expectOne((r) => r.url.endsWith('/transfusions/blood-groups/patient/p1'))
      .flush({ aboGroup: 'O', rhFactor: 'NEGATIVE' });
    expect(result).toEqual(jasmine.objectContaining({ aboGroup: 'O' }));
  });
});
