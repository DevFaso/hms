import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { PatientService } from './patient.service';

/**
 * QA D504: an allergy list narrowed by the patient's sharing opt-out must not
 * read as "no allergies". The server says so in X-Records-Withheld.
 */
describe('PatientService.listAllergies — withheld notice (QA D504)', () => {
  let service: PatientService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('reports withheld when the server says other hospitals were held back', () => {
    let result: { items: unknown[]; withheld: boolean } | undefined;
    service.listAllergies('p1', 'h1').subscribe((r) => (result = r));
    httpMock
      .expectOne('/patients/p1/allergies?hospitalId=h1')
      .flush([], { headers: { 'X-Records-Withheld': 'OTHER_HOSPITALS' } });
    expect(result).toEqual({ items: [], withheld: true });
  });

  it('reports the full list as not withheld without the header', () => {
    let result: { items: unknown[]; withheld: boolean } | undefined;
    service.listAllergies('p1').subscribe((r) => (result = r));
    httpMock.expectOne('/patients/p1/allergies').flush([{ id: 'a1' }]);
    expect(result).toEqual({ items: [{ id: 'a1' }], withheld: false });
  });
});
