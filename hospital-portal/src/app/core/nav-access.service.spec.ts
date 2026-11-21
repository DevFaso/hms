import { TestBed } from '@angular/core/testing';

import { NavAccessService } from './nav-access.service';

/** QA r65 D2761: what the acting role can open, as the sidebar decides it. */
describe('NavAccessService (QA r65 D2761)', () => {
  let service: NavAccessService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(NavAccessService);
  });

  it('before any shell has published, every link is left to the route guards', () => {
    expect(service.snapshot()).toBeNull();
    expect(service.canOpen('/billing')).toBeTrue();
  });

  it('a module the sidebar lists is open, with every page under it', () => {
    service.publish(['/dashboard', '/patients', '/pharmacy/dispensing']);
    expect(service.canOpen('/patients')).toBeTrue();
    expect(service.canOpen('/patients/new')).toBeTrue();
    expect(service.canOpen('/patients/42?tab=visits')).toBeTrue();
    expect(service.canOpen('/pharmacy/dispensing')).toBeTrue();
  });

  it('a module the sidebar does not list is not open, even when its name starts the same', () => {
    service.publish(['/patients', '/pharmacy/dispensing']);
    expect(service.canOpen('/billing')).toBeFalse();
    expect(service.canOpen('/appointments/new')).toBeFalse();
    // A sibling under the same first segment is another module.
    expect(service.canOpen('/pharmacy/inventory')).toBeFalse();
    // A prefix of a name is not a parent path.
    expect(service.canOpen('/patients-archive')).toBeFalse();
  });

  it('the top bar’s pages are always open: they are never sidebar entries', () => {
    service.publish(['/patients']);
    for (const path of [
      '/chat',
      '/notifications',
      '/announcements',
      '/profile',
      '/settings',
      '/dashboard',
    ]) {
      expect(service.canOpen(path)).withContext(path).toBeTrue();
    }
  });

  it('a new sidebar replaces the old one, and sign-out forgets it', () => {
    service.publish(['/billing']);
    const first = service.snapshot();
    service.publish(['/patients']);
    expect(service.snapshot()).not.toBe(first);
    expect(service.canOpen('/billing')).toBeFalse();

    service.clear();
    expect(service.snapshot()).toBeNull();
    expect(service.canOpen('/billing')).toBeTrue();
  });
});
