import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DepartmentLookupService } from './department-lookup.service';

/**
 * The shared department picker source.
 *
 * <p>Exists because encounters and admissions each derived their department
 * list from the STAFF list, which hides any department nobody is assigned to.
 * These specs pin the contract both now depend on.
 */
describe('DepartmentLookupService', () => {
  let service: DepartmentLookupService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DepartmentLookupService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('calls the active-minimal endpoint for the hospital', () => {
    // active-minimal, not by-hospital: it returns only ACTIVE departments and
    // already permits DOCTOR / NURSE / MIDWIFE / RECEPTIONIST, which is
    // exactly who fills in these forms.
    let result: { id: string; name: string }[] = [];
    service.getActiveDepartments('h1').subscribe((d) => (result = d));

    const req = httpMock.expectOne('/departments/active-minimal/h1');
    expect(req.request.method).toBe('GET');
    req.flush({ data: [{ id: 'd9', name: 'General Practices' }] });

    expect(result).toEqual([{ id: 'd9', name: 'General Practices' }]);
  });

  it('returns a department that has no staff assigned to it', () => {
    // The whole point. The previous staff-derived list could not produce this
    // row at all, so a real active department was unselectable.
    let result: { id: string; name: string }[] = [];
    service.getActiveDepartments('h1').subscribe((d) => (result = d));
    httpMock
      .expectOne('/departments/active-minimal/h1')
      .flush({ data: [{ id: 'empty-dept', name: 'General Practices' }] });

    expect(result.map((d) => d.id)).toContain('empty-dept');
  });

  it('treats a missing data envelope as an empty list', () => {
    // Collected rather than assigned to a nullable, so the assertion is about
    // what was emitted and TypeScript has nothing to narrow away.
    const emissions: { id: string; name: string }[][] = [];
    service.getActiveDepartments('h1').subscribe((d) => emissions.push(d));
    httpMock.expectOne('/departments/active-minimal/h1').flush({});

    expect(emissions.length).toBe(1);
    expect(emissions[0]).toEqual([]);
  });

  it('propagates errors instead of swallowing them into an empty list', () => {
    // An empty picker and a failed request look identical to the user and
    // mean opposite things. Callers render the difference, so the error has
    // to reach them.
    let errored = false;
    service.getActiveDepartments('h1').subscribe({
      next: () => fail('should not emit'),
      error: () => (errored = true),
    });
    httpMock
      .expectOne('/departments/active-minimal/h1')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(errored).toBeTrue();
  });
});
