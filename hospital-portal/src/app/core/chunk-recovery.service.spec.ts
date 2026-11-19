import { TestBed } from '@angular/core/testing';
import { NavigationEnd, NavigationError, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { CHUNK_RECOVERY_BROWSER, ChunkRecoveryService } from './chunk-recovery.service';

describe('ChunkRecoveryService', () => {
  let events: Subject<unknown>;
  let saved: string | null;
  let browser: {
    read: jasmine.Spy;
    write: jasmine.Spy;
    clear: jasmine.Spy;
    navigate: jasmine.Spy;
    online: jasmine.Spy;
    reachable: jasmine.Spy;
  };
  /** The reachability probe answers asynchronously. */
  const settle = async () => {
    for (let i = 0; i < 3; i++) await Promise.resolve();
  };
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
      online: jasmine.createSpy().and.returnValue(true),
      reachable: jasmine.createSpy().and.callFake(() => Promise.resolve(true)),
    };
    TestBed.configureTestingModule({
      providers: [
        {
          provide: Router,
          useValue: {
            events,
            config: [
              { path: 'procedure-orders' },
              { path: 'patients/:id' },
              { path: 'reception/empi-candidates' },
            ],
          },
        },
        { provide: CHUNK_RECOVERY_BROWSER, useValue: browser },
      ],
    });
    service = TestBed.inject(ChunkRecoveryService);
  });

  it('navigates once to the failed internal URL, including its selected scope', async () => {
    service.handle(failure());
    await settle();
    expect(browser.navigate).toHaveBeenCalledOnceWith('/procedure-orders?hospitalId=cdx-1');
    service.handle(failure());
    await settle();
    expect(browser.navigate).toHaveBeenCalledTimes(1);
    expect(service.failedUrl()).toBe('/procedure-orders?hospitalId=cdx-1');
    service.reload();
    await settle();
    expect(browser.navigate).toHaveBeenCalledTimes(2);
  });

  it('only rearms after the failed route successfully loads, not an unrelated page', async () => {
    service.handle(failure());
    await settle();
    events.next(new NavigationEnd(2, '/patients', '/patients'));
    service.handle(failure());
    await settle();
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
    await settle();
    expect(browser.navigate).toHaveBeenCalledTimes(2);
  });

  it('shows recovery without automatic reload when storage is unavailable', async () => {
    browser.read.and.throwError('Storage denied');
    expect(() => service.handle(failure())).not.toThrow();
    expect(service.failedUrl()).not.toBeNull();
    expect(browser.navigate).not.toHaveBeenCalled();
  });

  it('does not reload on API/permission errors or external destinations', async () => {
    service.handle(new NavigationError(1, '/procedure-orders', new Error('403 Forbidden')));
    await settle();
    service.handle(failure('//example.com'));
    await settle();
    service.handle(failure('https://example.com'));
    await settle();
    expect(browser.navigate).not.toHaveBeenCalled();
    expect(service.failedUrl()).toBeNull();
  });
  it('allows another route and bounds A-B-A cycles without storing identities', async () => {
    service.handle(failure('/procedure-orders?hospitalId=cdx-secret'));
    await settle();
    service.handle(failure('/patients/cdx-sensitive?search=private'));
    await settle();
    service.handle(failure('/procedure-orders?hospitalId=cdx-other'));
    await settle();
    expect(browser.navigate).toHaveBeenCalledTimes(2);
    expect(saved).not.toContain('cdx-');
    expect(saved).not.toContain('search');
    expect(JSON.parse(saved!)).toEqual([
      { route: 'procedure-orders', at: jasmine.any(Number) },
      { route: 'patients/:id', at: jasmine.any(Number) },
    ]);
  });
  it('expires attempts after one minute and migrates legacy guards', async () => {
    const now = spyOn(Date, 'now').and.returnValue(100_000);
    saved = '1';
    service.handle(failure());
    await settle();
    expect(browser.navigate).toHaveBeenCalledTimes(1);
    now.and.returnValue(160_000);
    service.handle(failure());
    await settle();
    expect(browser.navigate).toHaveBeenCalledTimes(2);
    saved = '/patients/old-private-id';
    service.handle(failure());
    await settle();
    expect(browser.navigate).toHaveBeenCalledTimes(3);
    expect(saved).not.toContain('old-private-id');
  });

  it('QA D2352: offline, stays on the page with an offline message and never reloads', async () => {
    browser.online.and.returnValue(false);
    service.handle(failure());
    await settle();
    expect(browser.navigate).not.toHaveBeenCalled();
    expect(service.failedUrl()).toBe('/procedure-orders?hospitalId=cdx-1');
    expect(service.offline()).toBeTrue();
    service.reload();
    await settle();
    expect(browser.navigate).not.toHaveBeenCalled();

    browser.online.and.returnValue(true);
    service.reload();
    await settle();
    expect(browser.navigate).toHaveBeenCalledOnceWith('/procedure-orders?hospitalId=cdx-1');
    expect(service.offline()).toBeFalse();
  });

  it('QA D2352: "online" but the server does not answer: no blank reload', async () => {
    browser.reachable.and.callFake(() => Promise.resolve(false));
    service.handle(failure());
    await settle();
    expect(browser.navigate).not.toHaveBeenCalled();
    expect(service.offline()).toBeTrue();
    events.next(new NavigationEnd(2, '/patients', '/patients'));
    expect(service.offline()).toBeFalse();
  });
});
