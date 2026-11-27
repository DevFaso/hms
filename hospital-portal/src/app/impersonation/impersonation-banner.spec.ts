import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';

import { ImpersonationBannerComponent } from './impersonation-banner';
import { ImpersonationService } from '../services/impersonation.service';
import { closeScreens, openScreen } from '../testing/screen-frame';

interface MockImpersonationService {
  active: ReturnType<typeof signal>;
  // MVP-4b — banner reads these computed signals via the service.
  remainingMs: ReturnType<typeof signal>;
  nearingExpiry: ReturnType<typeof signal>;
  stop: jasmine.Spy;
  forceStop: jasmine.Spy;
  refreshActive: jasmine.Spy;
}

describe('ImpersonationBannerComponent', () => {
  let service: MockImpersonationService;
  let router: Router;

  function setup(
    activeValue: {
      impersonating: boolean;
      targetUsername?: string;
      impersonatorUsername?: string;
    } | null,
  ) {
    service.active.set(activeValue);
    TestBed.configureTestingModule({
      imports: [ImpersonationBannerComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ImpersonationService, useValue: service },
      ],
    });
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.stub();
    const fixture = TestBed.createComponent(ImpersonationBannerComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    service = {
      active: signal(null),
      remainingMs: signal(null),
      nearingExpiry: signal(false),
      stop: jasmine.createSpy('stop').and.returnValue(of({ impersonating: false })),
      forceStop: jasmine.createSpy('forceStop'),
      refreshActive: jasmine
        .createSpy('refreshActive')
        .and.returnValue(of({ impersonating: false })),
    };
  });

  afterEach(() => {
    closeScreens();
    TestBed.resetTestingModule();
  });

  it('hides when no impersonation is active', () => {
    const fixture = setup(null);
    expect(fixture.nativeElement.querySelector('[data-test="impersonation-banner"]')).toBeNull();
  });

  it('renders the target username when impersonating', () => {
    const fixture = setup({
      impersonating: true,
      targetUsername: 'nurse.alice',
      impersonatorUsername: 'super.admin',
    });
    const banner = fixture.nativeElement.querySelector('[data-test="impersonation-banner"]');
    expect(banner).not.toBeNull();
    expect(banner.textContent).toContain('nurse.alice');
    expect(banner.textContent).toContain('super.admin');
  });

  it('exit() calls stop() and routes back to /super-admin', () => {
    service.stop.and.returnValue(of({ impersonating: false }));
    const fixture = setup({ impersonating: true, targetUsername: 'nurse.alice' });
    const cmp = fixture.componentInstance;

    cmp.exit();

    expect(service.stop).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/super-admin');
  });

  it('exit() falls back to forceStop() when stop() fails', () => {
    service.stop.and.returnValue(throwError(() => new Error('boom')));
    const fixture = setup({ impersonating: true, targetUsername: 'nurse.alice' });
    const cmp = fixture.componentInstance;

    cmp.exit();

    expect(service.forceStop).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/super-admin');
  });
  // r81 D4272: on a phone the way out of the support session stays on the screen.
  for (const width of [390, 768, 1440]) {
    it(`keeps the exit button inside a ${width} px screen, whole and on one line`, async () => {
      service.remainingMs.set(29 * 60_000);
      const fixture = setup({
        impersonating: true,
        targetUsername: 'r81s.r81sreset.r81.a-long-generated-username',
        impersonatorUsername: 'super.administrator.of.the.platform',
      });
      const screen = await openScreen(width);
      screen.body.appendChild(fixture.nativeElement);

      const banner = screen.querySelector<HTMLElement>('[data-test="impersonation-banner"]')!;
      const exit = screen.querySelector<HTMLElement>('.exit-btn')!;
      const bannerBox = banner.getBoundingClientRect();
      const exitBox = exit.getBoundingClientRect();

      expect(exitBox.width).toBeGreaterThan(0);
      expect(exitBox.left).toBeGreaterThanOrEqual(bannerBox.left);
      expect(exitBox.right).toBeLessThanOrEqual(Math.min(bannerBox.right, width) + 0.5);
      expect(bannerBox.right).toBeLessThanOrEqual(width + 0.5);
      // The label is not cut or folded: the button is as wide as its content.
      expect(exit.scrollWidth).toBeLessThanOrEqual(exit.clientWidth + 1);
      expect(screen.documentElement.scrollWidth).toBeLessThanOrEqual(width);
    });
  }
});
