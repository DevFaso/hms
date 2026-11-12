import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { IdleService } from './idle.service';

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
