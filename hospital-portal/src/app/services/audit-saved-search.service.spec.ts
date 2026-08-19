import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuditSavedSearchService } from './audit-saved-search.service';
import { AuditSearchFilter } from './audit-search.model';

const LEGACY_STORAGE_KEY = 'super_admin_audit_saved_searches';
const MIGRATION_FLAG_KEY = 'super_admin_audit_saved_searches_migrated';
const BASE = '/super-admin/audit-search/saved';

describe('AuditSavedSearchService (MVP-8c REST)', () => {
  let service: AuditSavedSearchService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem(LEGACY_STORAGE_KEY);
    localStorage.removeItem(MIGRATION_FLAG_KEY);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuditSavedSearchService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    localStorage.removeItem(LEGACY_STORAGE_KEY);
    localStorage.removeItem(MIGRATION_FLAG_KEY);
    httpMock.verify();
  });

  it('list() deserialises filterJson into a typed filter', () => {
    let received: unknown;
    service.list().subscribe((rows) => (received = rows));

    const req = httpMock.expectOne(BASE);
    expect(req.request.method).toBe('GET');
    req.flush([
      {
        id: 'id-1',
        ownerUsername: 'alice@example.com',
        name: 'GDPR alerts',
        filterJson: '{"userName":"alice","tenantRegion":"EU"}',
        shared: false,
        createdAt: '2026-05-01T00:00:00Z',
        updatedAt: '2026-05-01T00:00:00Z',
      },
    ]);

    expect(Array.isArray(received)).toBeTrue();
    const rows = received as { name: string; filter: AuditSearchFilter }[];
    expect(rows[0].name).toBe('GDPR alerts');
    expect(rows[0].filter.userName).toBe('alice');
    expect(rows[0].filter.tenantRegion).toBe('EU');
  });

  it('list() tolerates malformed filterJson and returns an empty filter', () => {
    let received: unknown;
    service.list().subscribe((rows) => (received = rows));

    httpMock.expectOne(BASE).flush([
      {
        id: 'id-2',
        ownerUsername: 'bob@example.com',
        name: 'broken row',
        filterJson: '{not json',
        shared: true,
        createdAt: '2026-05-01T00:00:00Z',
        updatedAt: '2026-05-01T00:00:00Z',
      },
    ]);

    const rows = received as { filter: AuditSearchFilter }[];
    expect(rows[0].filter).toEqual({});
  });

  it('create() rejects a blank name without making a request', () => {
    expect(() => service.create('   ', {})).toThrowError(/required/);
    httpMock.expectNone(BASE);
  });

  it('create() strips pagination from the persisted snapshot', () => {
    service.create('Pag-stripped', { userName: 'alice', page: 7, size: 100 }).subscribe();

    const req = httpMock.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.name).toBe('Pag-stripped');
    const filter = JSON.parse(req.request.body.filterJson) as AuditSearchFilter;
    expect(filter.userName).toBe('alice');
    expect(filter.page).toBeUndefined();
    expect(filter.size).toBeUndefined();
    req.flush({
      id: 'new-1',
      ownerUsername: 'alice@example.com',
      name: 'Pag-stripped',
      filterJson: req.request.body.filterJson,
      shared: false,
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    });
  });

  it('update() sends the trimmed name and stripped filter', () => {
    service.update('id-1', '  edited  ', { userName: 'a' }, true).subscribe();
    const req = httpMock.expectOne(`${BASE}/id-1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.name).toBe('edited');
    expect(req.request.body.shared).toBeTrue();
    req.flush({
      id: 'id-1',
      ownerUsername: 'alice@example.com',
      name: 'edited',
      filterJson: req.request.body.filterJson,
      shared: true,
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    });
  });

  it('delete() issues DELETE on the right path', () => {
    service.delete('id-2').subscribe();
    const req = httpMock.expectOne(`${BASE}/id-2`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  // ── localStorage → REST migration shim ──────────────────────────────

  it('migrateLegacyEntries() is a no-op when the migration flag is set', () => {
    localStorage.setItem(MIGRATION_FLAG_KEY, 'true');
    let received: unknown;
    service.migrateLegacyEntries().subscribe((r) => (received = r));
    expect(received).toEqual([]);
    httpMock.expectNone(BASE);
  });

  it('migrateLegacyEntries() is a no-op when there are no legacy entries', () => {
    let received: unknown;
    service.migrateLegacyEntries().subscribe((r) => (received = r));
    expect(received).toEqual([]);
    expect(localStorage.getItem(MIGRATION_FLAG_KEY)).toBe('true');
    httpMock.expectNone(BASE);
  });

  it('migrateLegacyEntries() uploads each legacy entry then clears the legacy key', () => {
    localStorage.setItem(
      LEGACY_STORAGE_KEY,
      JSON.stringify([
        { id: 'legacy-1', name: 'one', filter: { userName: 'a' } },
        { id: 'legacy-2', name: 'two', filter: { userName: 'b' } },
      ]),
    );

    let received: unknown;
    service.migrateLegacyEntries().subscribe((r) => (received = r));

    const reqs = httpMock.match(BASE);
    expect(reqs.length).toBe(2);
    reqs.forEach((req, i) => {
      expect(req.request.method).toBe('POST');
      req.flush({
        id: `srv-${i}`,
        ownerUsername: 'alice@example.com',
        name: req.request.body.name,
        filterJson: req.request.body.filterJson,
        shared: false,
        createdAt: '2026-05-01T00:00:00Z',
        updatedAt: '2026-05-01T00:00:00Z',
      });
    });

    expect(Array.isArray(received)).toBeTrue();
    expect((received as unknown[]).length).toBe(2);
    expect(localStorage.getItem(LEGACY_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(MIGRATION_FLAG_KEY)).toBe('true');
  });

  it('migrateLegacyEntries() partial failure: keeps the legacy key when ALL uploads fail', () => {
    // Copilot review fix — each upload is wrapped in catchError so a
    // single bad row doesn't error the whole batch. When *every*
    // upload fails the legacy key stays intact for a retry.
    localStorage.setItem(
      LEGACY_STORAGE_KEY,
      JSON.stringify([
        { id: 'legacy-1', name: 'one', filter: { userName: 'a' } },
        { id: 'legacy-2', name: 'two', filter: { userName: 'b' } },
      ]),
    );

    let received: unknown;
    service.migrateLegacyEntries().subscribe((r) => (received = r));

    const reqs = httpMock.match(BASE);
    expect(reqs.length).toBe(2);
    reqs.forEach((req) => req.flush('boom', { status: 500, statusText: 'Server Error' }));

    expect(Array.isArray(received)).toBeTrue();
    expect((received as unknown[]).length).toBe(0);
    // Legacy key preserved + flag NOT set so the next mount retries.
    expect(localStorage.getItem(LEGACY_STORAGE_KEY)).not.toBeNull();
    expect(localStorage.getItem(MIGRATION_FLAG_KEY)).toBeNull();
  });

  it('migrateLegacyEntries() partial failure: clears the legacy key when at least one upload succeeded', () => {
    localStorage.setItem(
      LEGACY_STORAGE_KEY,
      JSON.stringify([
        { id: 'legacy-1', name: 'one', filter: { userName: 'a' } },
        { id: 'legacy-2', name: 'two', filter: { userName: 'b' } },
      ]),
    );

    let received: unknown;
    service.migrateLegacyEntries().subscribe((r) => (received = r));

    const reqs = httpMock.match(BASE);
    expect(reqs.length).toBe(2);
    // First upload fails, second succeeds.
    reqs[0].flush('boom', { status: 500, statusText: 'Server Error' });
    reqs[1].flush({
      id: 'srv-1',
      ownerUsername: 'alice@example.com',
      name: reqs[1].request.body.name,
      filterJson: reqs[1].request.body.filterJson,
      shared: false,
      createdAt: '2026-05-01T00:00:00Z',
      updatedAt: '2026-05-01T00:00:00Z',
    });

    // Only the successful row in the result; legacy key cleared so a
    // re-run doesn't duplicate the surviving server row.
    expect((received as unknown[]).length).toBe(1);
    expect(localStorage.getItem(LEGACY_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(MIGRATION_FLAG_KEY)).toBe('true');
  });

  it('migrateLegacyEntries() tolerates corrupt legacy JSON', () => {
    localStorage.setItem(LEGACY_STORAGE_KEY, '{broken');
    let received: unknown;
    service.migrateLegacyEntries().subscribe((r) => (received = r));
    expect(received).toEqual([]);
    // Corrupt JSON yields an empty list → no uploads, but flag is set
    // so a future call doesn't keep trying.
    expect(localStorage.getItem(MIGRATION_FLAG_KEY)).toBe('true');
    httpMock.expectNone(BASE);
  });
});
