import { TestBed } from '@angular/core/testing';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { CHUNK_RECOVERY_BROWSER, ChunkRecoveryService } from './chunk-recovery.service';

describe('ChunkRecoveryService', () => {
  let events: Subject<unknown>;
  let saved: string | null;
  let browser: { read: jasmine.Spy; write: jasmine.Spy; clear: jasmine.Spy; navigate: jasmine.Spy };
  let service: ChunkRecoveryService;
  const failure = (url = '/procedure-orders?hospitalId=cdx-1') =>
    new NavigationError(
      1,
      url,
      new Error('Failed to fetch dynamically imported module: /chunk-old.js'),
    );

  beforeEach(() => {
    events = new Subject();
    saved = null;
    browser = {
      read: jasmine.createSpy().and.callFake(() => saved),
      write: jasmine.createSpy().and.callFake((url: string) => {
        saved = url;
      }),
      clear: jasmine.createSpy().and.callFake(() => {
        saved = null;
      }),
      navigate: jasmine.createSpy(),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { events } },
        { provide: CHUNK_RECOVERY_BROWSER, useValue: browser },
      ],
    });
    service = TestBed.inject(ChunkRecoveryService);
  });

  it('navigates once to the failed internal URL, including its selected scope', () => {
    service.handle(failure());
    expect(browser.navigate).toHaveBeenCalledOnceWith('/procedure-orders?hospitalId=cdx-1');
    service.handle(failure());
    expect(browser.navigate).toHaveBeenCalledTimes(1);
    expect(service.failedUrl()).toBe('/procedure-orders?hospitalId=cdx-1');
    service.reload();
    expect(browser.navigate).toHaveBeenCalledTimes(2);
  });

  it('only rearms after the failed route successfully loads, not an unrelated page', () => {
    service.handle(failure());
    events.next(new NavigationEnd(2, '/patients', '/patients'));
    service.handle(failure());
    expect(browser.navigate).toHaveBeenCalledTimes(1);
    events.next(
      new NavigationEnd(
        3,
        '/procedure-orders?hospitalId=cdx-1',
        '/procedure-orders?hospitalId=cdx-1',
      ),
    );
    expect(saved).toBeNull();
    service.handle(failure());
    expect(browser.navigate).toHaveBeenCalledTimes(2);
  });

  it('shows recovery without automatic reload when storage is unavailable', () => {
    browser.read.and.throwError('Storage denied');
    expect(() => service.handle(failure())).not.toThrow();
    expect(service.failedUrl()).not.toBeNull();
    expect(browser.navigate).not.toHaveBeenCalled();
  });

  it('does not reload on API/permission errors or external destinations', () => {
    service.handle(new NavigationError(1, '/procedure-orders', new Error('403 Forbidden')));
    service.handle(failure('//example.com'));
    service.handle(failure('https://example.com'));
    expect(browser.navigate).not.toHaveBeenCalled();
    expect(service.failedUrl()).toBeNull();
  });
});
