import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ReceptionService } from '../reception/reception.service';
import { AuditSearchService } from '../services/audit-search.service';
import { EncounterService } from '../services/encounter.service';
import { UserService } from '../services/user.service';

/**
 * The callers whose search or lookup is keyed by a personal identifier: each POSTs the
 * identifier in the body, and the request URL carries none of it.
 */
describe('identifier searches and lookups keep identifiers out of the URL', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('ReceptionService.getDuplicateCandidates', () => {
    TestBed.inject(ReceptionService)
      .getDuplicateCandidates({ name: 'Awa Kaboré', dob: '1991-07-22', phone: '' })
      .subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/reception/patients/duplicate-candidates'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Awa Kaboré', dob: '1991-07-22' });
    expect(req.request.params.keys()).toEqual([]);
    req.flush([]);
  });

  it('UserService.search', () => {
    TestBed.inject(UserService)
      .search(1, 20, { name: 'awa', email: 'a@b.c', role: 'ROLE_NURSE', onlyDeleted: true })
      .subscribe();
    const req = http.expectOne((r) => r.url === '/users/search');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'awa', role: 'ROLE_NURSE', email: 'a@b.c' });
    expect(req.request.urlWithParams).toBe('/users/search?page=1&size=20&onlyDeleted=true');
    req.flush({ content: [] });
  });

  it('AuditSearchService.search and exportCsv', () => {
    const service = TestBed.inject(AuditSearchService);
    service
      .search({
        userName: '70161099',
        eventTypes: ['USER_DELETE'],
        excludeRoutineSuccesses: true,
        page: 0,
        size: 50,
      })
      .subscribe();
    const search = http.expectOne((r) => r.url === '/api/super-admin/audit-search');
    expect(search.request.method).toBe('POST');
    expect(search.request.body).toEqual({
      userName: '70161099',
      eventTypes: ['USER_DELETE'],
      excludeRoutineSuccesses: true,
    });
    expect(search.request.urlWithParams).toBe('/api/super-admin/audit-search?page=0&size=50');
    search.flush({ content: [] });

    service.exportCsv({ userName: 'awa' }, 100).subscribe();
    const csv = http.expectOne((r) => r.url === '/api/super-admin/audit-search/csv');
    expect(csv.request.method).toBe('POST');
    expect(csv.request.body).toEqual({ userName: 'awa' });
    expect(csv.request.urlWithParams).toBe('/api/super-admin/audit-search/csv?maxRows=100');
    csv.flush(new Blob());
  });

  it('EncounterService.getByDoctor', () => {
    TestBed.inject(EncounterService).getByDoctor('dr.awa@example.org').subscribe();
    const req = http.expectOne((r) => r.url.endsWith('/encounters/by-doctor'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ identifier: 'dr.awa@example.org' });
    req.flush([]);
  });
});
