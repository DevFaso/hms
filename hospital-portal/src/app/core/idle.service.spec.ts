import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IdleService, PAGE_RELOAD } from './idle.service';

describe('IdleService repository-only session coverage', () => {
  let idle: IdleService;
  beforeEach(() => {
    sessionStorage.removeItem('hms_idle_locked');
    sessionStorage.removeItem('hms_lock_ts');
    TestBed.configureTestingModule({});
    idle = TestBed.inject(IdleService);
  });
  afterEach(() => {
    idle.stop();
    sessionStorage.removeItem('hms_idle_locked');
    sessionStorage.removeItem('hms_lock_ts');
  });

  it('locks at the idle boundary and persists the lock', fakeAsync(() => {
    idle.start(60_000);
    tick(59_999);
    expect(idle.locked()).toBeFalse();
    tick(1);
    expect(idle.locked()).toBeTrue();
    expect(sessionStorage.getItem('hms_idle_locked')).toBe('true');
    expect(sessionStorage.getItem('hms_lock_ts')).not.toBeNull();
  }));

  it('activity renews the window before expiry', fakeAsync(() => {
    idle.start(60_000);
    tick(59_000);
    document.dispatchEvent(new Event('mousedown', { bubbles: true }));
    tick(59_000);
    expect(idle.locked()).toBeFalse();
    tick(1_000);
    expect(idle.locked()).toBeTrue();
  }));

  it('unlock clears persisted state and starts a fresh idle window', fakeAsync(() => {
    idle.start(60_000);
    idle.lock();
    idle.unlock();
    expect(idle.locked()).toBeFalse();
    expect(sessionStorage.getItem('hms_idle_locked')).toBeNull();
    expect(sessionStorage.getItem('hms_lock_ts')).toBeNull();
    tick(60_000);
    expect(idle.locked()).toBeTrue();
  }));

  it('a fresh service restores a saved lock and activity does not unlock it', fakeAsync(() => {
    const previous = idle;
    previous.stop();
    sessionStorage.setItem('hms_idle_locked', 'true');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    idle = TestBed.inject(IdleService);
    expect(idle).not.toBe(previous);
    idle.start(60_000);
    expect(idle.locked()).toBeTrue();
    document.dispatchEvent(new Event('keydown', { bubbles: true }));
    tick(120_000);
    expect(idle.locked()).toBeTrue();
  }));

  it('stop removes activity listeners and cancels the pending idle timer', fakeAsync(() => {
    const added = spyOn(document, 'addEventListener').and.callThrough();
    const removed = spyOn(document, 'removeEventListener').and.callThrough();
    idle.start(60_000);
    const registered = added.calls.allArgs();
    expect(registered.map(([event]) => event)).toContain('mousedown');
    expect(registered.map(([event]) => event)).toContain('visibilitychange');
    idle.stop();
    for (const [event, listener] of registered) {
      expect(removed).toHaveBeenCalledWith(event, listener);
    }
    document.dispatchEvent(new Event('mousedown', { bubbles: true }));
    tick(120_000);
    expect(idle.locked()).toBeFalse();
  }));
});

/**
 * r81 D4262: the lock is the session's, kept by the server. The tab reports
 * it, hides the page behind it, and opens again only through `unlock()`,
 * which the lock screen calls after the server accepted the password.
 */
describe('IdleService — the lock is reported to the server and hides the page', () => {
  let idle: IdleService;
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.removeItem('hms_idle_locked');
    sessionStorage.removeItem('hms_lock_ts');
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    idle = TestBed.inject(IdleService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    idle.stop();
    sessionStorage.removeItem('hms_idle_locked');
    sessionStorage.removeItem('hms_lock_ts');
  });

  it('an idle lock is reported to the server and takes the page out', fakeAsync(() => {
    idle.start(60_000);
    expect(idle.contentHidden()).toBeFalse();
    tick(60_000);

    expect(idle.locked()).toBeTrue();
    expect(idle.contentHidden()).toBeTrue();
    const report = http.expectOne('/auth/session/lock');
    expect(report.request.method).toBe('POST');
    report.flush(null);
    http.verify();
  }));

  it('a report that did not get through is sent again when the lock screen opens', fakeAsync(() => {
    idle.start(60_000);
    idle.lock();
    http.expectOne('/auth/session/lock').error(new ProgressEvent('error'));

    idle.ensureServerLock();
    http.expectOne('/auth/session/lock').flush(null);
    idle.ensureServerLock();
    http.expectNone('/auth/session/lock');
  }));

  it('a lock the server reports is shown whatever the tab remembered, and its unlock reloads the page', fakeAsync(() => {
    const reload = spyOn(PAGE_RELOAD, 'reload');
    idle.start(60_000);
    expect(sessionStorage.getItem('hms_idle_locked')).toBeNull();

    idle.lockFromServer();

    expect(idle.locked()).toBeTrue();
    expect(idle.contentHidden()).toBeTrue();
    http.expectNone('/auth/session/lock');

    idle.unlock();
    expect(reload).toHaveBeenCalledTimes(1);
    // Until the reload the page stays out of the document.
    expect(idle.contentHidden()).toBeTrue();
  }));

  it('a lock restored from the tab after a reload is reported, and its unlock reloads the page', fakeAsync(() => {
    const reload = spyOn(PAGE_RELOAD, 'reload');
    sessionStorage.setItem('hms_idle_locked', 'true');
    idle.start(60_000);

    expect(idle.locked()).toBeTrue();
    expect(idle.contentHidden()).toBeTrue();
    http.expectOne('/auth/session/lock').flush(null);

    idle.unlock();
    expect(reload).toHaveBeenCalledTimes(1);
  }));

  it('a verified unlock of a lock this page set puts the page back without a reload', fakeAsync(() => {
    const reload = spyOn(PAGE_RELOAD, 'reload');
    idle.start(60_000);
    idle.lock();
    http.expectOne('/auth/session/lock').flush(null);
    // A request refused meanwhile changes nothing: the page keeps its state.
    idle.lockFromServer();

    idle.unlock();

    expect(reload).not.toHaveBeenCalled();
    expect(idle.locked()).toBeFalse();
    expect(idle.contentHidden()).toBeFalse();
  }));

  it('leaving by signing out forgets the lock and never puts the page back', fakeAsync(() => {
    idle.start(60_000);
    idle.lock();
    http.expectOne('/auth/session/lock').flush(null);

    idle.release();

    expect(idle.locked()).toBeFalse();
    expect(idle.contentHidden()).toBeTrue();
    expect(sessionStorage.getItem('hms_idle_locked')).toBeNull();
    // The next shell starts with its own page in view.
    idle.stop();
    idle.start(60_000);
    expect(idle.contentHidden()).toBeFalse();
  }));
});
