import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PatientDocumentsService } from './patient-documents.service';

describe('PatientDocumentsService', () => {
  let service: PatientDocumentsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientDocumentsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it("lists a patient's documents newest first, with an optional type filter", () => {
    service.list('p1', 'REFERRAL_LETTER', 0, 50).subscribe();
    const req = http.expectOne(
      (r) =>
        r.url === '/patients/p1/documents' && r.params.get('documentType') === 'REFERRAL_LETTER',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('sort')).toBe('createdAt,desc');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 });
  });

  it('omits the type parameter when no filter is set', () => {
    service.list('p1').subscribe();
    const req = http.expectOne((r) => r.url === '/patients/p1/documents');
    expect(req.request.params.has('documentType')).toBeFalse();
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 });
  });

  it('downloads bytes as a blob through the authenticated route', () => {
    service.downloadBlob('p1', 'd1').subscribe();
    const req = http.expectOne('/patients/p1/documents/d1/download');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['%PDF']));
  });
});
