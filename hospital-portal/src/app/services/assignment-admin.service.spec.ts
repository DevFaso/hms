import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AssignmentAdminService } from './assignment-admin.service';

describe('AssignmentAdminService', () => {
  let service: AssignmentAdminService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), AssignmentAdminService],
    });
    service = TestBed.inject(AssignmentAdminService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs a page with only the provided filters', () => {
    service.list(1, 20, { hospitalId: 'h1', active: false }).subscribe((page) => {
      expect(page.totalElements).toBe(1);
    });
    const req = httpMock.expectOne('/assignments?page=1&size=20&hospitalId=h1&active=false');
    expect(req.request.method).toBe('GET');
    req.flush({ content: [{ id: 'a1' }], totalElements: 1, totalPages: 1, number: 1, size: 20 });
  });

  it('POSTs a single assignment', () => {
    service.create({ userIdentifier: 'jane@x.org', roleId: 'r1', hospitalId: 'h1' }).subscribe();
    const req = httpMock.expectOne('/assignments');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.userIdentifier).toBe('jane@x.org');
    req.flush({ id: 'a1', active: true, confirmationVerified: false });
  });

  it('POSTs multi-scope with hospital list', () => {
    service
      .createMultiScope({ userIdentifier: 'jane', roleId: 'r1', hospitalIds: ['h1', 'h2'] })
      .subscribe((res) => expect(res.createdAssignments).toBe(2));
    const req = httpMock.expectOne('/assignments/multi-scope');
    expect(req.request.body.hospitalIds).toEqual(['h1', 'h2']);
    req.flush({
      requestedAssignments: 2,
      createdAssignments: 2,
      skippedAssignments: 0,
      assignments: [],
      failures: [],
    });
  });

  it('sends regenerate-code with resendNotifications as a query param', () => {
    service.regenerateCode('a1', false).subscribe();
    const req = httpMock.expectOne('/assignments/a1/regenerate-code?resendNotifications=false');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ id: 'a1', active: true, confirmationVerified: false });
  });

  it('PATCHes deactivate (soft path)', () => {
    service.deactivate('a1').subscribe();
    const req = httpMock.expectOne('/assignments/a1/deactivate');
    expect(req.request.method).toBe('PATCH');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('POSTs bulk import as JSON with embedded CSV (not multipart)', () => {
    service
      .bulkImport({ csvContent: 'email,role_name\na@x.org,DOCTOR', delimiter: ',' })
      .subscribe();
    const req = httpMock.expectOne('/assignments/bulk-import');
    expect(req.request.method).toBe('POST');
    expect(typeof req.request.body.csvContent).toBe('string');
    req.flush({ processed: 1, created: 1, skipped: 0, failed: 0, results: [] });
  });
});
