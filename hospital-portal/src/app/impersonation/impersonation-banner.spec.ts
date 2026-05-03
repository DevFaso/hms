import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';

import { ImpersonationBannerComponent } from './impersonation-banner';
import { ImpersonationService } from '../services/impersonation.service';

interface MockImpersonationService {
  active: ReturnType<typeof signal>;
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
        provideHttpClient(),
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
      stop: jasmine.createSpy('stop').and.returnValue(of({ impersonating: false })),
      forceStop: jasmine.createSpy('forceStop'),
      refreshActive: jasmine
        .createSpy('refreshActive')
        .and.returnValue(of({ impersonating: false })),
    };
  });

  afterEach(() => TestBed.resetTestingModule());

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
});
