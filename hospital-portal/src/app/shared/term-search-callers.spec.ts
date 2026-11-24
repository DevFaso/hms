import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AppointmentService } from '../services/appointment.service';
import { AssignmentAdminService } from '../services/assignment-admin.service';
import { AuditLogService } from '../services/audit-log.service';
import { PrescriptionService } from '../services/prescription.service';

/**
 * QA D3750: a list's search box term (a name, a phone number, an MRN…) is posted to
 * `…/term-search` in the body; the URL keeps only the other filters and the paging. Without a
 * term the list is the plain GET, unchanged.
 */
describe('list search boxes keep the typed term out of the URL', () => {
  let http: HttpTestingController;
  const TERM = 'Patient Test 02';

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function expectTermPost(url: string) {
    const req = http.expectOne((r) => r.url === url);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ search: TERM });
    expect(req.request.params.has('search')).toBeFalse();
    expect(req.request.urlWithParams).not.toContain('Patient');
    return req;
  }

  it('AppointmentService.list', () => {
    const service = TestBed.inject(AppointmentService);
    service.list({ hospitalId: 'h1', search: ` ${TERM} ` }).subscribe();
    const req = expectTermPost('/appointments/term-search');
    expect(req.request.params.get('hospitalId')).toBe('h1');
    req.flush([]);

    service.list({ hospitalId: 'h1' }).subscribe();
    const plain = http.expectOne((r) => r.url === '/appointments');
    expect(plain.request.method).toBe('GET');
    plain.flush([]);
  });

  it('PrescriptionService.page', () => {
    const service = TestBed.inject(PrescriptionService);
    service.page({ statuses: ['SIGNED'], search: TERM }, 2, 50).subscribe();
    const req = expectTermPost('/prescriptions/term-search');
    expect(req.request.params.getAll('status')).toEqual(['SIGNED']);
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.getAll('sort')).toEqual(['createdAt,desc', 'id,desc']);
    req.flush({ content: [], totalElements: 0 });

    service.page({ search: '  ' }, 0, 50).subscribe();
    const plain = http.expectOne((r) => r.url === '/prescriptions');
    expect(plain.request.method).toBe('GET');
    plain.flush({ content: [], totalElements: 0 });
  });

  it('AuditLogService.list', () => {
    const service = TestBed.inject(AuditLogService);
    service.list({ page: 0, size: 20, eventType: 'USER_CREATE', search: TERM }).subscribe();
    const req = expectTermPost('/audit-logs/term-search');
    expect(req.request.params.get('eventType')).toBe('USER_CREATE');
    req.flush({ content: [], totalElements: 0, totalPages: 0 });

    service.list({ page: 0 }).subscribe();
    const plain = http.expectOne((r) => r.url === '/audit-logs');
    expect(plain.request.method).toBe('GET');
    plain.flush({ content: [], totalElements: 0, totalPages: 0 });
  });

  it('AssignmentAdminService.list', () => {
    const service = TestBed.inject(AssignmentAdminService);
    service.list(1, 20, { hospitalId: 'h1', search: TERM }).subscribe();
    const req = expectTermPost('/assignments/term-search');
    expect(req.request.params.get('hospitalId')).toBe('h1');
    expect(req.request.params.get('page')).toBe('1');
    req.flush({ content: [] });

    service.list(0, 20, {}).subscribe();
    const plain = http.expectOne((r) => r.url === '/assignments');
    expect(plain.request.method).toBe('GET');
    plain.flush({ content: [] });
  });
});
