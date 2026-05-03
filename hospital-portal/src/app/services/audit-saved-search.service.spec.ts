import { TestBed } from '@angular/core/testing';

import { AuditSavedSearchService, SavedAuditSearch } from './audit-saved-search.service';
import { AuditSearchFilter } from './audit-search.model';

const STORAGE_KEY = 'super_admin_audit_saved_searches';

describe('AuditSavedSearchService (MVP-8b)', () => {
  let service: AuditSavedSearchService;

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({});
    service = TestBed.inject(AuditSavedSearchService);
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  it('list() returns an empty array when nothing is persisted', () => {
    expect(service.list()).toEqual([]);
  });

  it('save() rejects a blank name', () => {
    expect(() => service.save('   ', {} as AuditSearchFilter)).toThrowError(/required/);
  });

  it('save() persists the filter without pagination fields', () => {
    const filter: AuditSearchFilter = {
      userName: 'alice',
      tenantRegion: 'EU',
      page: 7,
      size: 100,
    };

    const saved = service.save('GDPR alerts', filter);

    expect(saved.name).toBe('GDPR alerts');
    expect(saved.filter.userName).toBe('alice');
    expect(saved.filter.tenantRegion).toBe('EU');
    // Pagination is stripped on save — re-applying a saved search should
    // always start at page 0 with the component's default page size.
    expect(saved.filter.page).toBeUndefined();
    expect(saved.filter.size).toBeUndefined();
    expect(service.list().length).toBe(1);
  });

  it('save() with the same name overwrites the existing entry in place', () => {
    service.save('one', { userName: 'first' });
    service.save('one', { userName: 'second' });

    const list = service.list();
    expect(list.length).toBe(1);
    expect(list[0].filter.userName).toBe('second');
  });

  it('save() new entries push onto the head of the list', () => {
    service.save('first', { userName: 'a' });
    service.save('second', { userName: 'b' });

    const list = service.list();
    expect(list[0].name).toBe('second');
    expect(list[1].name).toBe('first');
  });

  it('delete() removes the matching id and leaves the rest intact', () => {
    const a = service.save('a', { userName: 'a' });
    const b = service.save('b', { userName: 'b' });

    service.delete(a.id);

    const remaining = service.list();
    expect(remaining.length).toBe(1);
    expect(remaining[0].id).toBe(b.id);
  });

  it('list() tolerates corrupt JSON in localStorage', () => {
    localStorage.setItem(STORAGE_KEY, '{not json');
    expect(service.list()).toEqual([]);
  });

  it('list() tolerates a non-array JSON value', () => {
    localStorage.setItem(STORAGE_KEY, '{"foo":"bar"}');
    expect(service.list()).toEqual([]);
  });
});
