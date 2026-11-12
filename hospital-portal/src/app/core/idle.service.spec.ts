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
    document.dispatchEvent(new Event('mousedown'));
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

  it('a saved lock survives service recreation until an explicit unlock', fakeAsync(() => {
    sessionStorage.setItem('hms_idle_locked', 'true');
    idle.start(60_000);
    expect(idle.locked()).toBeTrue();
    document.dispatchEvent(new Event('keydown'));
    tick(120_000);
    expect(idle.locked()).toBeTrue();
  }));

  it('stop removes activity listeners and cancels the pending idle timer', fakeAsync(() => {
    idle.start(60_000);
    idle.stop();
    document.dispatchEvent(new Event('mousedown'));
    tick(120_000);
    expect(idle.locked()).toBeFalse();
  }));
});
